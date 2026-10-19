/** The per-page-load client ids DeviceInfoService keys its broadcast holds on. */
import { describe, expect, it } from 'vitest';
import { infoClientId } from '../../main/frontend/service/infoClientId.js';

describe('infoClientId', () => {
  it('keeps one id per view for the page load, so a remount releases the hold it took', () => {
    let n = 0;
    const next = () => `id${++n}`;

    const board = infoClientId('test-board', next);
    expect(infoClientId('test-board', next)).toBe(board);
    expect(infoClientId('test-deck', next)).not.toBe(board);
  });

  it('prefixes the id with the view, so server logs name the holder', () => {
    expect(infoClientId('test-prefix', () => 'x')).toBe('test-prefix-x');
  });

  it('draws a random id by default', () => {
    const id = infoClientId('test-default');
    expect(id).toMatch(/^test-default-.+/);
  });
});
