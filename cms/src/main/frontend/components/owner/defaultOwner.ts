import type {PeekableCache} from "../../util/peekableCache";

/**
 * Wraps a model's empty-value creator so a new entity names the signed-in user as owner. The user
 * must already be known when the creator runs: Hilla's form snapshots the empty value once, when
 * it mounts, and reuses that snapshot for every "New" — an entity created before the user is known
 * would stay unowned (shared with all users) for the life of the form. So a form using this must
 * not mount before {@code currentUser.get()} has resolved; {@code CurrentUserGate} does that.
 *
 * An entity created while the user is unknown keeps the model's default and starts a lookup (a
 * failed one is retried); it never throws, and the lookup's rejection is handled here.
 */
export function withDefaultOwner<E extends {owner?: unknown}, U>(
    createEmpty: () => E,
    currentUser: PeekableCache<U | undefined>,
): () => E {
    return () => {
        const entity = createEmpty();
        const user = currentUser.peek();
        if (user.known) {
            if (user.value !== undefined) {
                entity.owner = user.value;
            }
        } else {
            // The rpc middleware toasts the cause; there is nothing else to undo.
            currentUser.get().catch(() => undefined);
        }
        return entity;
    };
}
