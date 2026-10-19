/** The current user is fetched once per page; a failed fetch must not be cached as the answer. */
import { describe, expect, it, vi } from 'vitest';
import { cacheUntilRejected } from '../../main/frontend/util/cacheUntilRejected.js';

describe('cacheUntilRejected', () => {
  it('calls the loader once while it succeeds', async () => {
    const load = vi.fn(() => Promise.resolve('user'));
    const get = cacheUntilRejected(load);

    expect(await get()).toBe('user');
    expect(await get()).toBe('user');
    expect(load).toHaveBeenCalledTimes(1);
  });

  it('shares one in-flight promise between concurrent callers', () => {
    const load = vi.fn(() => new Promise<string>(() => {}));
    const get = cacheUntilRejected(load);

    expect(get()).toBe(get());
    expect(load).toHaveBeenCalledTimes(1);
  });

  it('retries after a rejection', async () => {
    const load = vi.fn<() => Promise<string>>()
      .mockReturnValueOnce(Promise.reject(new Error('offline')))
      .mockReturnValueOnce(Promise.resolve('user'));
    const get = cacheUntilRejected(load);

    await expect(get()).rejects.toThrow('offline');
    expect(await get()).toBe('user');
    expect(load).toHaveBeenCalledTimes(2);
  });
});
