import {cacheUntilRejected} from "./cacheUntilRejected";

/** What a {@link PeekableCache} holds right now: nothing yet, or the value its load resolved to. */
export type Peek<T> = {known: false} | {known: true, value: T};

export interface PeekableCache<T> {
    /** The cached load, as {@link cacheUntilRejected} gives it: retried after a rejection. */
    get(): Promise<T>;
    /**
     * The resolved value, synchronously. It is set before any caller of {@link get} resumes, so
     * code that ran after awaiting {@link get} always finds it known.
     */
    peek(): Peek<T>;
}

/**
 * {@link cacheUntilRejected}, plus a synchronous read of the resolved value — for a callback that
 * cannot wait (a model's static {@code createEmptyValue}) but runs only after something awaited it.
 */
export function peekableCacheUntilRejected<T>(load: () => Promise<T>): PeekableCache<T> {
    let settled: Peek<T> = {known: false};
    // The value is recorded in the first reaction on the load, ahead of every caller's.
    const get = cacheUntilRejected(() => load().then(value => {
        settled = {known: true, value};
        return value;
    }));
    return {get, peek: () => settled};
}
