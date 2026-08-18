import {IFrame, IMessage, RxStomp, RxStompState} from "@stomp/rx-stomp";
import {Observable} from "rxjs/internal/Observable";
import {BehaviorSubject, Subscription} from "rxjs";

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
/** The backend polls the frequency converter every 400 ms, so three missed rounds. */
const FREQUENCY_CONVERTER_STALE_AFTER_MS = 1200;
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
    frequencyConverter: FeedStatus;
}

const NEVER_RECEIVED: FeedStatus = {everReceived: false, stale: false, staleForSeconds: null};

export const DISCONNECTED_LIVE_STATUS: LiveStatus = {
    connected: false,
    loadCell: NEVER_RECEIVED,
    frequencyConverter: NEVER_RECEIVED,
};

function sameFeed(a: FeedStatus, b: FeedStatus): boolean {
    return a.everReceived === b.everReceived
        && a.stale === b.stale
        && a.staleForSeconds === b.staleForSeconds;
}

export default class StatusService {
    private rxStomp: RxStomp;
    private loadCellTopic: Observable<IMessage>;
    private updateLog: Observable<IMessage>;
    private frequencyConverterInfoTopic: Observable<IMessage>;
    private connectedComponents: Set<object> = new Set();

    // performance.now(), not Date.now(): a monotonic clock, so a system time adjustment cannot
    // make a dead feed look fresh (or vice versa).
    private lastLoadCellAt: number | null = null;
    private lastFrequencyConverterAt: number | null = null;
    private socketOpen = false;
    private feedSubscriptions: Subscription[] = [];
    private freshnessTimer: ReturnType<typeof setInterval> | null = null;
    private readonly liveStatusSubject = new BehaviorSubject<LiveStatus>(DISCONNECTED_LIVE_STATUS);

    constructor() {
        this.rxStomp = new RxStomp();
        this.rxStomp.configure({
            brokerURL: resolveBrokerUrl(),
        });

        this.loadCellTopic = this.rxStomp
            .watch({destination: "/topic/load-cell"});

        this.frequencyConverterInfoTopic = this.rxStomp
            .watch({destination: "/topic/frequency-converter-info"});

        this.updateLog = this.rxStomp
            .watch({destination: "/topic/logs"});

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
                // inherit freshness earned before the gap.
                this.lastLoadCellAt = null;
                this.lastFrequencyConverterAt = null;
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
            this.frequencyConverterInfoTopic.subscribe(() => {
                this.lastFrequencyConverterAt = performance.now();
                this.publishLiveStatus();
            }),
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
        this.lastFrequencyConverterAt = null;
        this.publishLiveStatus();
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
            frequencyConverter: this.feedStatus(this.lastFrequencyConverterAt, FREQUENCY_CONVERTER_STALE_AFTER_MS),
        };

        // Emitting only on change keeps a healthy feed from re-rendering every consumer at frame
        // rate; once stale, the second counter is what makes it emit, at most once per second.
        const previous = this.liveStatusSubject.value;
        if (previous.connected === next.connected
            && sameFeed(previous.loadCell, next.loadCell)
            && sameFeed(previous.frequencyConverter, next.frequencyConverter)) {
            return;
        }

        this.liveStatusSubject.next(next);
    }

    get loadCellObservable() {
        return this.loadCellTopic;
    }

    get logObservable(){
        return this.updateLog;
    }

    get frequencyConverterInfoObservable(){
        return this.frequencyConverterInfoTopic;
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