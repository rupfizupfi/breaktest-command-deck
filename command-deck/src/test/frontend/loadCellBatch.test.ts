/**
 * Pure-logic tests for the `/topic/load-cell` batch parser (node environment — no browser, no DOM).
 *
 * Two invariants the subscribers cannot state: a batch that is not an array stays an empty batch
 * rather than a throw inside a STOMP callback, and an empty batch yields no force at all, so a
 * readout keeps its last value instead of falling to zero.
 */
import { describe, expect, it } from 'vitest';
import { latestForce, parseBatch, peakForce } from '../../main/frontend/service/loadCellBatch.js';

describe('parseBatch', () => {
  it('returns the samples of a batch', () => {
    expect(parseBatch('[{"timestamp":1000,"force":10}]')).toEqual([{timestamp: 1_000, force: 10}]);
  });

  it('returns an empty batch for a body that is not an array', () => {
    expect(parseBatch('{}')).toEqual([]);
  });
});

describe('latestForce', () => {
  it('is undefined for an empty batch', () => {
    expect(latestForce([])).toBeUndefined();
  });

  it('returns the last sample, not the largest', () => {
    expect(latestForce([
      {timestamp: 1_000, force: 10},
      {timestamp: 1_100, force: 30},
      {timestamp: 1_200, force: 20}
    ])).toBe(20);
  });
});

describe('peakForce', () => {
  it('is undefined for an empty batch', () => {
    expect(peakForce([])).toBeUndefined();
  });

  it('returns the largest force whatever its position', () => {
    expect(peakForce([
      {timestamp: 1_000, force: 10},
      {timestamp: 1_100, force: 30},
      {timestamp: 1_200, force: 20}
    ])).toBe(30);
    expect(peakForce([
      {timestamp: 1_000, force: 30},
      {timestamp: 1_100, force: 10}
    ])).toBe(30);
  });

  it('keeps a negative peak rather than clamping at zero', () => {
    expect(peakForce([{timestamp: 1_000, force: -5}, {timestamp: 1_100, force: -2}])).toBe(-2);
  });
});
