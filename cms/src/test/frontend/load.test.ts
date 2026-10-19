/**
 * Route-id views must tell "still loading", "doesn't exist" and "could not be loaded" apart: a
 * failed or pending call once rendered as "Project … doesn't exist".
 */
import { describe, expect, it } from 'vitest';
import { parseRouteId, settleLoad } from '../../main/frontend/util/load.js';

describe('parseRouteId', () => {
  it('accepts an integer id', () => {
    expect(parseRouteId('42')).toBe(42);
  });

  it.each([undefined, '', '  ', 'abc', '1.5', 'NaN', '12abc'])('rejects %j', (raw) => {
    expect(parseRouteId(raw)).toBeUndefined();
  });
});

describe('settleLoad', () => {
  it('maps a value to found', async () => {
    expect(await settleLoad(Promise.resolve({name: 'p'}))).toEqual({kind: 'found', value: {name: 'p'}});
  });

  it.each([undefined, null])('maps %j to not-found', async (value) => {
    expect(await settleLoad(Promise.resolve(value))).toEqual({kind: 'not-found'});
  });

  it('maps a rejection to error instead of rejecting', async () => {
    await expect(settleLoad(Promise.reject(new Error('boom')))).resolves.toEqual({kind: 'error'});
  });

  it('keeps falsy but present values as found', async () => {
    expect(await settleLoad(Promise.resolve(0))).toEqual({kind: 'found', value: 0});
  });
});
