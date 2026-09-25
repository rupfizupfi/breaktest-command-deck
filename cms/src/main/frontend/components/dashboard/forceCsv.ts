/**
 * Splits a force CSV into parallel timestamp and force arrays, skipping any row whose columns
 * do not both parse as numbers.
 *
 * Force is a Java float written by Float.toString, so E-notation is a routine near-zero reading
 * and must be kept — see command-deck LoadCellThread.
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
