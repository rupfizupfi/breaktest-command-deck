/**
 * The one parser for `/topic/load-cell` batches: every consumer reads the wire through here, so the
 * `force`/`timestamp` keys of `Measurement` are pinned to a single frontend site. Relative import
 * and no React, STOMP or generated-client dependency, so Vitest's node environment loads it.
 */
import type {ForceSample} from "../components/dashboard/traceBuffer.js";

export type {ForceSample};

/** The batch, or empty for anything that is not a JSON array. */
export function parseBatch(body: string): ForceSample[] {
    const parsed = JSON.parse(body);
    return Array.isArray(parsed) ? parsed as ForceSample[] : [];
}

/** Newest force in the batch; undefined for an empty one. */
export function latestForce(batch: ForceSample[]): number | undefined {
    return batch.length === 0 ? undefined : batch[batch.length - 1].force;
}

/** Highest force in the batch; undefined for an empty one. */
export function peakForce(batch: ForceSample[]): number | undefined {
    if (batch.length === 0) {
        return undefined;
    }

    let peak = batch[0].force;
    for (let i = 1; i < batch.length; i++) {
        if (batch[i].force > peak) {
            peak = batch[i].force;
        }
    }
    return peak;
}
