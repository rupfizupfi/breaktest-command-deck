/**
 * The states of a view that fetches one entity by its route id. `error` is separate from
 * `not-found` so a failed call never reads as "doesn't exist"; the rpc middleware
 * (`rpcErrorPolicy`) has already toasted the cause, so a view only renders the state.
 */
export type Load<T> =
    | {kind: 'loading'}
    | {kind: 'found', value: T}
    | {kind: 'not-found'}
    | {kind: 'error'};

/** A route id, or undefined when it is not an integer (a typo'd URL is a not-found, not a call). */
export function parseRouteId(raw: string | undefined): number | undefined {
    if (raw === undefined || raw.trim() === '') {
        return undefined;
    }
    const id = Number(raw);
    return Number.isInteger(id) ? id : undefined;
}

/** Settles a fetch into a Load; never rejects, so a caller has no unhandled rejection to leak. */
export function settleLoad<T>(request: Promise<T | null | undefined>): Promise<Load<T>> {
    return request.then(
        (value): Load<T> => value === undefined || value === null ? {kind: 'not-found'} : {kind: 'found', value},
        (): Load<T> => ({kind: 'error'}));
}
