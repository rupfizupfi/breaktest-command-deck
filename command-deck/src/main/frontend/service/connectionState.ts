/**
 * The pure rules behind StatusService: feed freshness, test-state ordering and the one
 * ConnectionState a view renders. Kept free of STOMP, React and runtime generated-client imports so
 * Vitest's node environment can load it.
 */
import type TestState from "../generated/ch/rupfizupfi/deck/testrunner/TestState.js";

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

export const NEVER_RECEIVED: FeedStatus = {everReceived: false, stale: false, staleForSeconds: null};

export const DISCONNECTED_LIVE_STATUS: LiveStatus = {
    connected: false,
    loadCell: NEVER_RECEIVED,
    frequencyInverter: NEVER_RECEIVED,
};

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

export const CONNECTION_OK: ConnectionState = {kind: 'ok'};
export const CONNECTION_SERVER_LOST: ConnectionState = {kind: 'server-lost'};

/** Keep in sync with TestState#isIncident (Java); the two lists are the same three states. */
const INCIDENT_STATES: ReadonlySet<string> = new Set(['SENSOR_LOST', 'SAFE_HOLD', 'RESUMING']);

export function isIncident(state: TestState | null | undefined): boolean {
    return state != null && INCIDENT_STATES.has(state);
}

export function sameFeed(a: FeedStatus, b: FeedStatus): boolean {
    return a.everReceived === b.everReceived
        && a.stale === b.stale
        && a.staleForSeconds === b.staleForSeconds;
}

export function sameConnectionState(a: ConnectionState, b: ConnectionState): boolean {
    if (a.kind === 'feed-stale' && b.kind === 'feed-stale') {
        return a.seconds === b.seconds;
    }
    if (a.kind === 'sensor-lost' && b.kind === 'sensor-lost') {
        return a.reason === b.reason && a.canResume === b.canResume && a.deadlineMillis === b.deadlineMillis;
    }
    return a.kind === b.kind;
}

/** `lastAt` and `now` come from the same monotonic clock (performance.now()). */
export function feedStatus(lastAt: number | null, staleAfterMs: number, now: number): FeedStatus {
    if (lastAt === null) {
        return NEVER_RECEIVED;
    }

    const ageMs = now - lastAt;
    if (ageMs <= staleAfterMs) {
        return {everReceived: true, stale: false, staleForSeconds: null};
    }

    return {everReceived: true, stale: true, staleForSeconds: Math.floor(ageMs / 1000)};
}

/**
 * False when `frame` is older than one already applied for the same run. Null means "the server says
 * no run is active" — a contradiction, so it always applies and clears an incident.
 */
export function shouldApplyTestState(previous: TestStateFrame | null, frame: TestStateFrame | null): boolean {
    return frame === null || previous === null
        || frame.testResultId !== previous.testResultId || frame.sequence >= previous.sequence;
}

/**
 * Precedence: sensor-lost > server-lost > feed-stale > ok.
 *
 * A sensor loss is asserted by the server and a dropped socket is no evidence the sensor came
 * back, so the incident outranks and survives every connection problem. Only a newer frame or a
 * `TestRunnerService.status()` read (plain HTTP, works while the WebSocket is down) clears it.
 */
export function deriveConnectionState(frame: TestStateFrame | null, socketOpen: boolean, loadCell: FeedStatus): ConnectionState {
    if (frame !== null && isIncident(frame.state)) {
        return {
            kind: 'sensor-lost',
            reason: frame.reason ?? 'the load cell stopped delivering measurements',
            canResume: frame.canResume,
            deadlineMillis: frame.safeHoldDeadlineMillis,
        };
    }

    if (!socketOpen) {
        return CONNECTION_SERVER_LOST;
    }

    if (loadCell.stale) {
        return {kind: 'feed-stale', seconds: loadCell.staleForSeconds ?? 0};
    }

    return CONNECTION_OK;
}

/** Throws on a body that is not JSON; missing fields take the values a fresh run would carry. */
export function parseTestStateFrame(body: string): TestStateFrame {
    const raw = JSON.parse(body);
    return {
        state: raw.state,
        reason: raw.reason ?? null,
        canResume: raw.canResume === true,
        lossCount: raw.lossCount ?? 0,
        reconnectAttempt: raw.reconnectAttempt ?? 0,
        lastKnownForce: raw.lastKnownForce ?? null,
        safeHoldDeadlineMillis: raw.safeHoldDeadlineMillis ?? null,
        testResultId: raw.testResultId ?? 0,
        sequence: raw.sequence ?? 0,
    };
}

/**
 * Counts the consumers sharing the one socket. Counted per key, not a set: two mounts under the same
 * key (a component rendered twice) must both leave before the socket closes.
 */
export class ConsumerRefCount {
    private readonly counts = new Map<object, number>();
    private total = 0;

    /** True for the first consumer overall: the caller opens the socket. */
    acquire(key: object): boolean {
        this.counts.set(key, (this.counts.get(key) ?? 0) + 1);
        this.total++;
        return this.total === 1;
    }

    /** True when the last consumer left: the caller closes the socket. An unknown key is a no-op. */
    release(key: object): boolean {
        const count = this.counts.get(key);
        if (count === undefined) {
            return false;
        }
        if (count === 1) {
            this.counts.delete(key);
        } else {
            this.counts.set(key, count - 1);
        }
        this.total--;
        return this.total === 0;
    }

    get size(): number {
        return this.total;
    }
}
