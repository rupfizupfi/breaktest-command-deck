/**
 * The run view's force trace: a chronological, capacity-capped point buffer fed by
 * `/topic/load-cell` batches. Imports nothing from React, Chart.js or the generated client, so it
 * loads in Vitest's node environment.
 */

/** Structurally Chart.js's `Point`, declared locally to keep this module framework-free. */
export interface TracePoint {
    x: number;
    y: number;
}

/** One `/topic/load-cell` element: server-clock `System.currentTimeMillis()` and newtons. */
export interface ForceSample {
    timestamp: number;
    force: number;
}

/**
 * Trace capacity, ~11 minutes at the DSCUSB's rated 200 samples/s. Raising it is paid twice: ~40
 * bytes per point held for the run, and a full min-max decimation rescan on each of the ~16 frames
 * a second the broadcaster sends. Eviction never moves the headline number — the running max is
 * kept separately and covers the whole run, not the visible window.
 */
export const MAX_SAMPLES = 131_072;

/** Evict a block per overflow, not a point per sample: one O(n) splice per EVICT_BLOCK ingests. */
export const EVICT_BLOCK = 8_192;

/**
 * How far behind a batch's newest sample a sample may sit and still belong to this run. The opening
 * batch can lead with the *previous* run's stranded tail, and anchoring the x axis on one of those
 * pushes the whole trace off screen. Sized between the ~60 ms an in-run batch spans and the gap to
 * a previous run, which is at least the operator's time to start it.
 * See doc/06-feature-work/testrunner-safety/staleness-and-lifecycle-findings.md.
 */
export const STRANDED_TAIL_MS = 500;

/**
 * Matches LoadCellThread's NO_DATA_TIMEOUT_MS, so every hole the chart breaks at is one the
 * backend's no-data watchdog also declares.
 */
export const GAP_MS = 250;

export interface TraceBuffer {
    /** Chronological, capped at MAX_SAMPLES. Chart.js holds this reference — never replace it. */
    points: TracePoint[];
    /** Objects freed by the last eviction, refilled in place so a long run stops allocating. */
    recycled: TracePoint[];
    /** First accepted sample's server timestamp; the x origin. Null until the first sample. */
    anchor: number | null;
    /** Last accepted sample's x. Separators do not move it, so a hole is measured sample to sample. */
    lastX: number | null;
    max: number;
    last: number;
    hasValue: boolean;
}

export function createBuffer(): TraceBuffer {
    return {
        points: [],
        recycled: [],
        anchor: null,
        lastX: null,
        max: Number.NEGATIVE_INFINITY,
        last: 0,
        hasValue: false
    };
}

export function resetBuffer(buffer: TraceBuffer): void {
    // In place: Chart.js holds `points` by reference, so replacing the array would orphan the chart.
    buffer.points.length = 0;
    buffer.recycled.length = 0;
    buffer.anchor = null;
    buffer.lastX = null;
    buffer.max = Number.NEGATIVE_INFINITY;
    buffer.last = 0;
    buffer.hasValue = false;
}

function pushPoint(buffer: TraceBuffer, x: number, y: number): void {
    if (buffer.points.length >= MAX_SAMPLES) {
        buffer.recycled = buffer.points.splice(0, EVICT_BLOCK);
    }

    const reused = buffer.recycled.pop();
    if (reused) {
        reused.x = x;
        reused.y = y;
        buffer.points.push(reused);
    } else {
        buffer.points.push({x, y});
    }
}

export function append(buffer: TraceBuffer, x: number, y: number): void {
    pushPoint(buffer, x, y);

    if (y > buffer.max) {
        buffer.max = y;
    }
    buffer.last = y;
    buffer.lastX = x;
    buffer.hasValue = true;
}

/** A NaN y Chart.js breaks the line at. Nothing else on the buffer moves, so no readout sees it. */
function appendGap(buffer: TraceBuffer, x: number): void {
    pushPoint(buffer, x, NaN);
}

/** Index of the first sample of this run, or -1 when the batch is entirely a stranded tail. */
export function firstOwnSample(batch: ForceSample[]): number {
    const newest = batch[batch.length - 1].timestamp;
    for (let i = 0; i < batch.length; i++) {
        if (newest - batch[i].timestamp <= STRANDED_TAIL_MS) {
            return i;
        }
    }
    return -1;
}

export function ingest(buffer: TraceBuffer, batch: ForceSample[]): void {
    if (batch.length === 0) {
        return;
    }

    let from = 0;
    if (buffer.anchor === null) {
        from = firstOwnSample(batch);
        if (from < 0) {
            return;
        }
        buffer.anchor = batch[from].timestamp;
    }

    // Index loop, never spread: the batch is unbounded from this component's point of view, and
    // `push(...batch)` throws RangeError once it is large enough to matter.
    for (let i = from; i < batch.length; i++) {
        const x = batch[i].timestamp - buffer.anchor;
        if (buffer.lastX !== null && x - buffer.lastX > GAP_MS) {
            appendGap(buffer, (buffer.lastX + x) / 2);
        }
        append(buffer, x, batch[i].force);
    }
}
