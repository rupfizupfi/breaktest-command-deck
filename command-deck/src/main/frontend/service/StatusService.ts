import {IFrame, IMessage, RxStomp, RxStompState} from "@stomp/rx-stomp";
import {Observable} from "rxjs/internal/Observable";
import {BehaviorSubject, Subscription} from "rxjs";
import TestState from "Frontend/generated/ch/rupfizupfi/deck/testrunner/TestState";

function resolveBrokerUrl(): string {
    // Leading slash: the endpoint is at the server root, while this service may first be
    // constructed from a nested route (e.g. /execute-test/run/17) whose base directory is not it.
    const url = new URL('/status', document.baseURI);
    url.protocol = url.protocol === 'https:' ? 'wss:' : 'ws:';
    return url.toString();
}

/**
 * A frozen number is indistinguishable from a live one, so every topic gets a staleness deadline.
 * Generous multiples of the real cadence: this exists to catch a feed that stopped, not to flag
 * ordinary scheduler jitter.
 */
const LOAD_CELL_STALE_AFTER_MS = 1500;
/** The backend polls the frequency inverter every 400 ms, so three missed rounds. */
const FREQUENCY_INVERTER_STALE_AFTER_MS = 1200;
/** Staleness has to be noticed without an incoming frame, so it is driven by a timer. */
const FRESHNESS_TICK_MS = 500;

export interface FeedStatus {
    /** False until the first frame arrives on the current connection. */
    everReceived: boolean;
    /** No frame has arrived for longer than this topic's deadline. */
    stale: boolean;
    /** Whole seconds since the last frame, or null while the feed is fresh. */
    staleForSeconds: number | null;
}

export interface LiveStatus {
    /** The WebSocket itself. Distinguishes "machine is quiet" from "we lost the server". */
    connected: boolean;
    loadCell: FeedStatus;
    frequencyInverter: FeedStatus;
}

const NEVER_RECEIVED: FeedStatus = {everReceived: false, stale: false, staleForSeconds: null};

export const DISCONNECTED_LIVE_STATUS: LiveStatus = {
    connected: false,
    loadCell: NEVER_RECEIVED,
    frequencyInverter: NEVER_RECEIVED,
};

function sameFeed(a: FeedStatus, b: FeedStatus): boolean {
    return a.everReceived === b.everReceived
        && a.stale === b.stale
        && a.staleForSeconds === b.staleForSeconds;
}

/**
 * One `/topic/test-state` frame, and the shape `TestRunnerService.status()` is normalized into so the
 * two paths cannot disagree. Mirrors the Java record `TestStateMessage`.
 */
export interface TestStateFrame {
    state: TestState;
    reason: string | null;
    /** The server's verdict. Never recomputed here — see TestRunnerThread#canResumeNow. */
    canResume: boolean;
    lossCount: number;
    reconnectAttempt: number;
    /** Newton, null before the first measurement of the run. */
    lastKnownForce: number | null;
    /** Epoch millis, null outside SAFE_HOLD. Wall clock, because the browser renders a countdown. */
    safeHoldDeadlineMillis: number | null;
    testResultId: number;
    /** Restarts at 0 with each run, which is why the run id is part of every ordering comparison. */
    sequence: number;
}

/**
 * What the operator is owed about the trustworthiness of what is on screen, in one value so a view
 * cannot render two contradictory warnings at once.
 */
export type ConnectionState =
    | {kind: 'ok'}
    | {kind: 'server-lost'}
    | {kind: 'feed-stale', seconds: number}
    | {kind: 'sensor-lost', reason: string, canResume: boolean, deadlineMillis: number | null};

const CONNECTION_OK: ConnectionState = {kind: 'ok'};
const CONNECTION_SERVER_LOST: ConnectionState = {kind: 'server-lost'};

/** Keep in sync with TestState#isIncident (Java); the two lists are the same three states. */
export function isIncident(state: TestState | null | undefined): boolean {
    return state === TestState.SENSOR_LOST || state === TestState.SAFE_HOLD || state === TestState.RESUMING;
}

function sameConnectionState(a: ConnectionState, b: ConnectionState): boolean {
    if (a.kind === 'feed-stale' && b.kind === 'feed-stale') {
        return a.seconds === b.seconds;
    }
    if (a.kind === 'sensor-lost' && b.kind === 'sensor-lost') {
        return a.reason === b.reason && a.canResume === b.canResume && a.deadlineMillis === b.deadlineMillis;
    }
    return a.kind === b.kind;
}

export default class StatusService {
    private rxStomp: RxStomp;
    private loadCellTopic: Observable<IMessage>;
    private updateLog: Observable<IMessage>;
    private frequencyInverterInfoTopic: Observable<IMessage>;
    private testStateTopic: Observable<IMessage>;
    private connectedComponents: Set<object> = new Set();

    // performance.now(), not Date.now(): a monotonic clock, so a system time adjustment cannot
    // make a dead feed look fresh (or vice versa).
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

        // rx-stomp reconnects silently after ~5 s, so without this the operator sees a frozen
        // screen recover with no indication that anything was ever wrong.
        this.rxStomp.connectionState$.subscribe((state: RxStompState) => {
            this.socketOpen = state === RxStompState.OPEN;
            if (!this.socketOpen) {
                // Timestamps belong to the connection that produced them; a reconnect must not
                // inherit freshness earned before the gap. Note what is NOT reset here: the last
                // test-state frame. See applyTestState.
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
     * A frame is dropped when it is older than one already applied for the same run; `sequence`
     * restarts at 0 with each run, hence the run-id comparison. Pass null for "the server says no run
     * is active" — that is a contradiction and does clear an incident.
     */
    applyTestState(frame: TestStateFrame | null): void {
        const previous = this.testStateSubject.value;
        if (frame !== null && previous !== null
            && frame.testResultId === previous.testResultId && frame.sequence < previous.sequence) {
            return;
        }

        this.testStateSubject.next(frame);
        this.publishLiveStatus();
    }

    /**
     * Timestamps every topic independently of who is rendering it. Deliberately not a tap() on the
     * exposed observables: those are shared/refCounted, so the tap would run once per subscriber
     * and not at all when no component happens to be mounted.
     */
    private trackFeedFreshness() {
        if (this.freshnessTimer !== null) {
            // connect() is reachable more than once without an intervening disconnect (the deck view
            // activates the client directly rather than through connectComponent), and double
            // registration would leak both a timer and two subscriptions per extra call.
            return;
        }

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
            const raw = JSON.parse(message.body);
            this.applyTestState({
                state: raw.state,
                reason: raw.reason ?? null,
                canResume: raw.canResume === true,
                lossCount: raw.lossCount ?? 0,
                reconnectAttempt: raw.reconnectAttempt ?? 0,
                lastKnownForce: raw.lastKnownForce ?? null,
                safeHoldDeadlineMillis: raw.safeHoldDeadlineMillis ?? null,
                testResultId: raw.testResultId ?? 0,
                sequence: raw.sequence ?? 0,
            });
        } catch (error) {
            console.error('unreadable /topic/test-state frame', error);
        }
    }

    private feedStatus(lastAt: number | null, staleAfterMs: number): FeedStatus {
        if (lastAt === null) {
            return NEVER_RECEIVED;
        }

        const ageMs = performance.now() - lastAt;
        if (ageMs <= staleAfterMs) {
            return {everReceived: true, stale: false, staleForSeconds: null};
        }

        return {everReceived: true, stale: true, staleForSeconds: Math.floor(ageMs / 1000)};
    }

    private publishLiveStatus() {
        const next: LiveStatus = {
            connected: this.socketOpen,
            loadCell: this.feedStatus(this.lastLoadCellAt, LOAD_CELL_STALE_AFTER_MS),
            frequencyInverter: this.feedStatus(this.lastFrequencyInverterAt, FREQUENCY_INVERTER_STALE_AFTER_MS),
        };

        // Before the early return below, and from the same snapshot: the freshness timer is what
        // advances the stale-second counter, and it must keep doing so even on a tick where the
        // LiveStatus itself did not change.
        this.publishConnectionState(next.loadCell);

        // Emitting only on change keeps a healthy feed from re-rendering every consumer at frame
        // rate; once stale, the second counter is what makes it emit, at most once per second.
        const previous = this.liveStatusSubject.value;
        if (previous.connected === next.connected
            && sameFeed(previous.loadCell, next.loadCell)
            && sameFeed(previous.frequencyInverter, next.frequencyInverter)) {
            return;
        }

        this.liveStatusSubject.next(next);
    }

    private publishConnectionState(loadCell: FeedStatus) {
        const next = this.deriveConnectionState(loadCell);
        if (sameConnectionState(this.connectionStateSubject.value, next)) {
            return;
        }
        this.connectionStateSubject.next(next);
    }

    /**
     * Precedence: sensor-lost > server-lost > feed-stale > ok.
     *
     * The first rank is the load-bearing one. A sensor loss is asserted by the server, and the socket
     * dropping is not evidence that the sensor came back — so the incident outranks, and survives,
     * every connection problem the browser can observe about itself. It is cleared only by a newer
     * frame or by a `TestRunnerService.status()` read, which still reaches the server over plain HTTP
     * while the WebSocket is down.
     */
    private deriveConnectionState(loadCell: FeedStatus): ConnectionState {
        const frame = this.testStateSubject.value;
        if (frame !== null && isIncident(frame.state)) {
            return {
                kind: 'sensor-lost',
                reason: frame.reason ?? 'the load cell stopped delivering measurements',
                canResume: frame.canResume,
                deadlineMillis: frame.safeHoldDeadlineMillis,
            };
        }

        if (!this.socketOpen) {
            return CONNECTION_SERVER_LOST;
        }

        if (loadCell.stale) {
            return {kind: 'feed-stale', seconds: loadCell.staleForSeconds ?? 0};
        }

        return CONNECTION_OK;
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

    connect() {
        this.rxStomp.activate();
        this.trackFeedFreshness();
    }

    disconnect() {
        this.stopTrackingFeedFreshness();
        this.rxStomp.deactivate();
        console.log("Disconnected");
    }

    sendStatusRequest(name: string) {
        this.rxStomp.publish({
            destination: "/topic/requests",
            body: JSON.stringify({'name': name})
        });
    }

    connectComponent(component: object) {
        if (this.connectedComponents.size === 0) {
            this.connect();
        }
        this.connectedComponents.add(component);
    }

    disconnectComponent(component: object) {
        this.connectedComponents.delete(component);
        if (this.connectedComponents.size === 0) {
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