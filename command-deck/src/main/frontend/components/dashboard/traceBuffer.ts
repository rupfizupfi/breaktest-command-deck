/**
 * The run view's force trace: a chronological, capacity-capped point buffer fed by
 * `/topic/load-cell` batches. Framework-free so it loads in Vitest's node environment.
 */

/** Structurally Chart.js's `Point`. */
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
 * Trace capacity, ~11 minutes at the DSCUSB's rated 200 samples/s. Raising it costs ~40 bytes per
 * point and a longer decimation rescan on each of the ~16 frames/s. Eviction does not affect `max`,
 * which covers the whole run.
 */
export const MAX_SAMPLES = 131_072;

/** Evict a block per overflow, not a point per sample: one O(n) splice per EVICT_BLOCK ingests. */
export const EVICT_BLOCK = 8_192;

/**
 * How far behind a batch's newest sample a sample may sit and still belong to this run. The opening
 * batch can lead with the previous run's tail, which would push the trace off screen if anchored on.
 * Sized between the ~60 ms an in-run batch spans and the gap between runs.
 */
export const STRANDED_TAIL_MS = 500;

/** Matches MeasurementLoop's NO_DATA_TIMEOUT_MS, so the chart breaks where the watchdog fires. */
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

/** A NaN y Chart.js breaks the line at; readouts never see it. */
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

    // Index loop, not spread: `push(...batch)` throws RangeError on a large enough batch.
    for (let i = from; i < batch.length; i++) {
        const x = batch[i].timestamp - buffer.anchor;
        if (buffer.lastX !== null && x - buffer.lastX > GAP_MS) {
            appendGap(buffer, (buffer.lastX + x) / 2);
        }
        append(buffer, x, batch[i].force);
    }
}
