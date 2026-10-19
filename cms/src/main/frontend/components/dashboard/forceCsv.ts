/**
 * Splits a force CSV into parallel timestamp and force arrays, skipping any row whose columns
 * do not both parse as numbers.
 *
 * Force is a Java float written by Float.toString, so E-notation is a routine near-zero reading
 * and must be kept — see command-deck MeasurementLoop.
 */
export function parseForceCsv(text: string): [number[], number[]] {
    const timestamps: number[] = [];
    const forces: number[] = [];

    for (const line of text.replace(/\r/g, '').split('\n')) {
        const [timestamp, force] = line.split(',').map((column) => parseFloat(column));

        if (isNaN(timestamp) || isNaN(force)) {
            continue;
        }

        timestamps.push(timestamp);
        forces.push(force);
    }

    return [timestamps, forces];
}

export interface ForceTrace {
    /** Milliseconds since the first sample. */
    elapsedMillis: number[];
    forces: number[];
    minForce: number;
    maxForce: number;
    durationMillis: number;
}

/**
 * The CSV as a chart-ready trace, or null when it holds no sample. Min and max come from one loop:
 * spreading a long run into Math.max overflows the call stack.
 */
export function toForceTrace([timestamps, forces]: [number[], number[]]): ForceTrace | null {
    if (timestamps.length === 0) {
        return null;
    }

    const start = timestamps[0];
    const elapsedMillis = new Array<number>(timestamps.length);
    let minForce = forces[0];
    let maxForce = forces[0];
    for (let i = 0; i < timestamps.length; i++) {
        elapsedMillis[i] = timestamps[i] - start;
        const force = forces[i];
        if (force < minForce) {
            minForce = force;
        } else if (force > maxForce) {
            maxForce = force;
        }
    }

    return {
        elapsedMillis,
        forces,
        minForce,
        maxForce,
        durationMillis: elapsedMillis[elapsedMillis.length - 1],
    };
}
