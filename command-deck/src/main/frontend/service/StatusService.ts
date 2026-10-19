import {IFrame, IMessage, RxStomp, RxStompState} from "@stomp/rx-stomp";
import {Observable} from "rxjs/internal/Observable";
import {BehaviorSubject, Subscription} from "rxjs";
import {
    CONNECTION_SERVER_LOST,
    ConnectionState,
    deriveConnectionState,
    DISCONNECTED_LIVE_STATUS,
    feedStatus,
    LiveStatus,
    parseTestStateFrame,
    sameConnectionState,
    sameFeed,
    shouldApplyTestState,
    TestStateFrame,
    ConsumerRefCount,
} from "Frontend/service/connectionState";

export type {ConnectionState, FeedStatus, LiveStatus, TestStateFrame} from "Frontend/service/connectionState";
export {DISCONNECTED_LIVE_STATUS, isIncident} from "Frontend/service/connectionState";

function resolveBrokerUrl(): string {
    // Absolute path: the service may first be constructed on a nested route (/execute-test/run/17).
    const url = new URL('/status', document.baseURI);
    url.protocol = url.protocol === 'https:' ? 'wss:' : 'ws:';
    return url.toString();
}

/**
 * A frozen number looks like a live one, so every topic gets a staleness deadline. Generous
 * multiples of the real cadence, to catch a stopped feed rather than scheduler jitter.
 */
const LOAD_CELL_STALE_AFTER_MS = 1500;
/** The backend polls the frequency inverter every 400 ms, so three missed rounds. */
const FREQUENCY_INVERTER_STALE_AFTER_MS = 1200;
/** Staleness has to be noticed without an incoming frame, so it is driven by a timer. */
const FRESHNESS_TICK_MS = 500;

export default class StatusService {
    private rxStomp: RxStomp;
    private loadCellTopic: Observable<IMessage>;
    private updateLog: Observable<IMessage>;
    private frequencyInverterInfoTopic: Observable<IMessage>;
    private testStateTopic: Observable<IMessage>;
    private readonly consumers = new ConsumerRefCount();

    // performance.now(), not Date.now(): monotonic, immune to system clock adjustments.
    private lastLoadCellAt: number | null = null;
    private lastFrequencyInverterAt: number | null = null;
    private socketOpen = false;
    private feedSubscriptions: Subscription[] = [];
    private freshnessTimer: ReturnType<typeof setInterval> | null = null;
    private readonly liveStatusSubject = new BehaviorSubject<LiveStatus>(DISCONNECTED_LIVE_STATUS);
    private readonly testStateSubject = new BehaviorSubject<TestStateFrame | null>(null);
    private readonly connectionStateSubject = new BehaviorSubject<ConnectionState>(CONNECTION_SERVER_LOST);

    constructor() {
        this.rxStomp = new RxStomp();
        this.rxStomp.configure({
            brokerURL: resolveBrokerUrl(),
        });

        this.loadCellTopic = this.rxStomp
            .watch({destination: "/topic/load-cell"});

        this.frequencyInverterInfoTopic = this.rxStomp
            .watch({destination: "/topic/frequency-inverter-info"});

        this.updateLog = this.rxStomp
            .watch({destination: "/topic/logs"});

        this.testStateTopic = this.rxStomp
            .watch({destination: "/topic/test-state"});

        this.rxStomp.stompErrors$.subscribe((frame: IFrame) => {
            console.error('Broker reported error: ' + frame.headers['message']);
            console.error('Additional details: ' + frame.body);
        });

        // rx-stomp reconnects silently after ~5 s; surface the gap to the operator.
        this.rxStomp.connectionState$.subscribe((state: RxStompState) => {
            this.socketOpen = state === RxStompState.OPEN;
            if (!this.socketOpen) {
                // A reconnect must not inherit freshness from before the gap. The last test-state
                // frame is kept on purpose; see deriveConnectionState.
                this.lastLoadCellAt = null;
                this.lastFrequencyInverterAt = null;
            }
            this.publishLiveStatus();
        });
    }

    /** Freshness of every topic plus the socket, for components that display live values. */
    get liveStatus(): Observable<LiveStatus> {
        return this.liveStatusSubject.asObservable();
    }

    get currentLiveStatus(): LiveStatus {
        return this.liveStatusSubject.value;
    }

    /** The last state the server asserted about the run, parsed and ordered. Null when none is known. */
    get testState(): Observable<TestStateFrame | null> {
        return this.testStateSubject.asObservable();
    }

    get currentTestState(): TestStateFrame | null {
        return this.testStateSubject.value;
    }

    /** Socket, feed freshness and run incident collapsed into the one thing a view should render. */
    get connectionState(): Observable<ConnectionState> {
        return this.connectionStateSubject.asObservable();
    }

    get currentConnectionState(): ConnectionState {
        return this.connectionStateSubject.value;
    }

    /**
     * Records what the server says about the run. The only writer of the incident state, fed both by
     * the `/topic/test-state` subscription below and by a `TestRunnerService.status()` read.
     *
     * Ordering rules: {@link shouldApplyTestState}.
     */
    applyTestState(frame: TestStateFrame | null): void {
        if (!shouldApplyTestState(this.testStateSubject.value, frame)) {
            return;
        }

        this.testStateSubject.next(frame);
        this.publishLiveStatus();
    }

    /**
     * Timestamps every topic independently of who is rendering it. Not a tap() on the exposed
     * observables: those are refCounted, so a tap would run once per subscriber, or never.
     */
    private trackFeedFreshness() {
        this.feedSubscriptions = [
            this.loadCellTopic.subscribe(() => {
                this.lastLoadCellAt = performance.now();
                this.publishLiveStatus();
            }),
            this.frequencyInverterInfoTopic.subscribe(() => {
                this.lastFrequencyInverterAt = performance.now();
                this.publishLiveStatus();
            }),
            this.testStateTopic.subscribe((message: IMessage) => this.ingestTestState(message)),
        ];

        this.freshnessTimer = setInterval(() => this.publishLiveStatus(), FRESHNESS_TICK_MS);
    }

    private stopTrackingFeedFreshness() {
        this.feedSubscriptions.forEach(subscription => subscription.unsubscribe());
        this.feedSubscriptions = [];

        if (this.freshnessTimer !== null) {
            clearInterval(this.freshnessTimer);
            this.freshnessTimer = null;
        }

        this.lastLoadCellAt = null;
        this.lastFrequencyInverterAt = null;
        this.publishLiveStatus();
    }

    /** A malformed frame must not take the banner down with it; the last good one keeps standing. */
    private ingestTestState(message: IMessage) {
        try {
            this.applyTestState(parseTestStateFrame(message.body));
        } catch (error) {
            console.error('unreadable /topic/test-state frame', error);
        }
    }

    private publishLiveStatus() {
        const now = performance.now();
        const next: LiveStatus = {
            connected: this.socketOpen,
            loadCell: feedStatus(this.lastLoadCellAt, LOAD_CELL_STALE_AFTER_MS, now),
            frequencyInverter: feedStatus(this.lastFrequencyInverterAt, FREQUENCY_INVERTER_STALE_AFTER_MS, now),
        };

        // Before the early return: the stale-second counter must advance even when LiveStatus
        // did not change.
        this.publishConnectionState(next.loadCell);

        // Emit only on change so a healthy feed does not re-render consumers at frame rate.
        const previous = this.liveStatusSubject.value;
        if (previous.connected === next.connected
            && sameFeed(previous.loadCell, next.loadCell)
            && sameFeed(previous.frequencyInverter, next.frequencyInverter)) {
            return;
        }

        this.liveStatusSubject.next(next);
    }

    private publishConnectionState(loadCell: LiveStatus['loadCell']) {
        const next = deriveConnectionState(this.testStateSubject.value, this.socketOpen, loadCell);
        if (sameConnectionState(this.connectionStateSubject.value, next)) {
            return;
        }
        this.connectionStateSubject.next(next);
    }

    get loadCellObservable() {
        return this.loadCellTopic;
    }

    get logObservable(){
        return this.updateLog;
    }

    get frequencyInverterInfoObservable(){
        return this.frequencyInverterInfoTopic;
    }

    get testStateObservable(){
        return this.testStateTopic;
    }

    // Private: the socket is shared, so only the refcount below may open or close it.
    private connect() {
        this.rxStomp.activate();
        this.trackFeedFreshness();
    }

    private disconnect() {
        this.stopTrackingFeedFreshness();
        void this.rxStomp.deactivate();
    }

    /** Opens the socket for the first consumer. Pair every call with {@link disconnectComponent}. */
    connectComponent(component: object) {
        if (this.consumers.acquire(component)) {
            this.connect();
        }
    }

    /** Closes the socket once the last consumer has left. */
    disconnectComponent(component: object) {
        if (this.consumers.release(component)) {
            this.disconnect();
        }
    }
}

let service: StatusService;

export function getService():StatusService
{
    if (!service) {
        service = new StatusService();
    }
    return service;
}