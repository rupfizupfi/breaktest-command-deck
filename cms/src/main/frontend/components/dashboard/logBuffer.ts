/**
 * The run log kept as its newest lines only: a long cyclic run writes tens of thousands of lines,
 * and rendering every one of them stalls the view that carries the Stop button. Pure, so Vitest's
 * node environment can load it.
 */

export interface LogLine {
    /** Position in the whole run's log; a stable React key that survives eviction. */
    id: number;
    text: string;
}

export interface LogBuffer {
    readonly lines: readonly LogLine[];
    /** Lines evicted so far. */
    readonly hidden: number;
}

export const DEFAULT_LOG_CAPACITY = 500;

export const EMPTY_LOG: LogBuffer = {lines: [], hidden: 0};

/** A new buffer with `text` appended; the oldest line goes once `capacity` is reached. */
export function appendLog(buffer: LogBuffer, text: string, capacity = DEFAULT_LOG_CAPACITY): LogBuffer {
    const line: LogLine = {id: buffer.hidden + buffer.lines.length, text};
    if (buffer.lines.length < capacity) {
        return {lines: [...buffer.lines, line], hidden: buffer.hidden};
    }
    const evicted = buffer.lines.length - capacity + 1;
    return {lines: [...buffer.lines.slice(evicted), line], hidden: buffer.hidden + evicted};
}
