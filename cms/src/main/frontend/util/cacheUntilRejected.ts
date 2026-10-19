/**
 * Memoizes an async call's promise, but forgets a rejected one, so a call that failed once (a
 * dropped connection at page load) is retried on the next use instead of failing forever.
 */
export function cacheUntilRejected<T>(load: () => Promise<T>): () => Promise<T> {
    let cached: Promise<T> | undefined;
    return () => {
        if (!cached) {
            const attempt = load();
            cached = attempt;
            attempt.catch(() => {
                if (cached === attempt) {
                    cached = undefined;
                }
            });
        }
        return cached;
    };
}
