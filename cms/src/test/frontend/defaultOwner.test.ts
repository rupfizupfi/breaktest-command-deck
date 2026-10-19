/**
 * A new row defaults its owner to the signed-in user. Hilla's form takes its empty value once, at
 * mount, so the owner must be known by then: a form mounted before the user lookup answered
 * created every new row shared (owner: all users), which a non-admin then could not edit or delete.
 */
import { describe, expect, it, vi } from 'vitest';
import { peekableCacheUntilRejected } from '../../main/frontend/util/peekableCache.js';
import { withDefaultOwner } from '../../main/frontend/components/owner/defaultOwner.js';

type User = { id: number; username: string };
type Entity = { name: string; owner?: User | null };

const user: User = { id: 1, username: 'user' };
const emptyEntity = (): Entity => ({ name: '', owner: undefined });

function deferred<T>() {
  let resolve!: (value: T) => void;
  let reject!: (reason: unknown) => void;
  const promise = new Promise<T>((res, rej) => {
    resolve = res;
    reject = rej;
  });
  return { promise, resolve, reject };
}

describe('peekableCacheUntilRejected', () => {
  it('is unknown until the load resolves, then known synchronously', async () => {
    const pending = deferred<User | undefined>();
    const cache = peekableCacheUntilRejected(() => pending.promise);

    const got = cache.get();
    expect(cache.peek()).toEqual({ known: false });
    pending.resolve(user);
    await got;
    expect(cache.peek()).toEqual({ known: true, value: user });
  });

  it('is known in every continuation of get(), however early it was registered', async () => {
    const pending = deferred<User | undefined>();
    const cache = peekableCacheUntilRejected(() => pending.promise);

    const seen = cache.get().then(() => cache.peek());
    pending.resolve(user);
    expect(await seen).toEqual({ known: true, value: user });
  });

  it('stays unknown after a rejection and retries on the next get()', async () => {
    const load = vi.fn<() => Promise<User | undefined>>()
      .mockReturnValueOnce(Promise.reject(new Error('offline')))
      .mockReturnValueOnce(Promise.resolve(user));
    const cache = peekableCacheUntilRejected(load);

    await expect(cache.get()).rejects.toThrow('offline');
    expect(cache.peek()).toEqual({ known: false });
    expect(await cache.get()).toBe(user);
    expect(cache.peek()).toEqual({ known: true, value: user });
    expect(load).toHaveBeenCalledTimes(2);
  });
});

describe('withDefaultOwner', () => {
  it('leaves the owner unset while the user is unknown — the race a form mounted too early loses', () => {
    const pending = deferred<User | undefined>();
    const create = withDefaultOwner(emptyEntity, peekableCacheUntilRejected(() => pending.promise));

    // The form's mount-time snapshot: taken before the lookup answered, it would be reused for
    // every "New" — so the gate must hold the form back until get() resolves.
    const snapshot = create();
    pending.resolve(user);
    expect(snapshot.owner).toBeUndefined();
  });

  it('sets the owner on an entity created once get() has resolved (the gated mount)', async () => {
    const cache = peekableCacheUntilRejected<User | undefined>(() => Promise.resolve(user));
    const create = withDefaultOwner(emptyEntity, cache);

    const mounted = cache.get().then(() => create());
    expect((await mounted).owner).toEqual(user);
  });

  it('starts the lookup when asked before it was, and handles its rejection', async () => {
    // Vitest fails the run on an unhandled rejection, so settling the failed lookup is the check.
    const load = vi.fn<() => Promise<User | undefined>>(() => Promise.reject(new Error('offline')));
    const create = withDefaultOwner(emptyEntity, peekableCacheUntilRejected(load));

    expect(create().owner).toBeUndefined();
    expect(load).toHaveBeenCalledTimes(1);
    await new Promise((r) => setTimeout(r, 0));
  });

  it('keeps the model default when there is no signed-in user', async () => {
    const cache = peekableCacheUntilRejected<User | undefined>(() => Promise.resolve(undefined));
    await cache.get();
    expect(withDefaultOwner(emptyEntity, cache)().owner).toBeUndefined();
  });
});
