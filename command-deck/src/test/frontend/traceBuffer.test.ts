/**
 * Pure-logic tests for the run view's force trace buffer (node environment — no browser, no DOM).
 *
 * Two invariants the chart cannot state: a hole the backend's no-data watchdog would declare must
 * reach Chart.js as a NaN separator so the line breaks over it, and no separator may reach the
 * Force/Max readouts, which are read straight off the buffer.
 */
import { describe, expect, it } from 'vitest';
import {
  createBuffer,
  EVICT_BLOCK,
  GAP_MS,
  ingest,
  MAX_SAMPLES,
  resetBuffer,
  STRANDED_TAIL_MS
} from '../../main/frontend/components/dashboard/traceBuffer.js';

const nanCount = (points: {y: number}[]) => points.filter((point) => Number.isNaN(point.y)).length;

describe('ingest', () => {
  it('inserts no separator for a contiguous batch and tracks max and last', () => {
    const buffer = createBuffer();

    ingest(buffer, [
      {timestamp: 1_000, force: 10},
      {timestamp: 1_100, force: 30},
      {timestamp: 1_200, force: 20}
    ]);

    expect(buffer.points).toEqual([{x: 0, y: 10}, {x: 100, y: 30}, {x: 200, y: 20}]);
    expect(buffer.max).toBe(30);
    expect(buffer.last).toBe(20);
    expect(buffer.lastX).toBe(200);
    expect(buffer.hasValue).toBe(true);
  });

  it('leaves a hole of exactly GAP_MS unbroken', () => {
    const buffer = createBuffer();

    ingest(buffer, [{timestamp: 1_000, force: 10}, {timestamp: 1_000 + GAP_MS, force: 20}]);

    expect(nanCount(buffer.points)).toBe(0);
  });

  it('breaks a hole longer than GAP_MS with one separator the readouts ignore', () => {
    const buffer = createBuffer();

    ingest(buffer, [{timestamp: 1_000, force: 10}, {timestamp: 1_400, force: 20}]);

    expect(nanCount(buffer.points)).toBe(1);
    expect(buffer.points[1].x).toBe(200);
    expect(buffer.points.map((point) => point.x)).toEqual([0, 200, 400]);
    expect(buffer.max).toBe(20);
    expect(buffer.last).toBe(20);
    expect(buffer.lastX).toBe(400);
  });

  it('anchors on the first own sample when the batch leads with a stranded tail', () => {
    const buffer = createBuffer();

    ingest(buffer, [
      {timestamp: 1_000, force: 99},
      {timestamp: 1_000 + STRANDED_TAIL_MS + 5_000, force: 10},
      {timestamp: 1_010 + STRANDED_TAIL_MS + 5_000, force: 12}
    ]);

    expect(buffer.anchor).toBe(1_000 + STRANDED_TAIL_MS + 5_000);
    expect(buffer.points).toEqual([{x: 0, y: 10}, {x: 10, y: 12}]);
  });

  it('evicts a block at MAX_SAMPLES, recycles its objects and stays chronological', () => {
    const buffer = createBuffer();
    // Anchored by its own batch: a single batch spanning the whole capacity would be read as a
    // stranded tail. 1 ms apart, so nothing in it is a hole.
    ingest(buffer, [{timestamp: 1_000, force: 0}]);
    ingest(buffer, Array.from({length: MAX_SAMPLES - 1}, (_unused, i) => ({
      timestamp: 1_001 + i,
      force: i + 1
    })));

    expect(buffer.points).toHaveLength(MAX_SAMPLES);
    // Last object of the block the next append evicts, so the first one popped off `recycled`.
    const recycledFirst = buffer.points[EVICT_BLOCK - 1];

    ingest(buffer, [{timestamp: 1_000 + MAX_SAMPLES, force: 1}]);

    expect(buffer.points).toHaveLength(MAX_SAMPLES - EVICT_BLOCK + 1);
    expect(buffer.points[buffer.points.length - 1]).toBe(recycledFirst);
    expect(buffer.points[0].x).toBe(EVICT_BLOCK);
    expect(buffer.points[buffer.points.length - 1].x).toBe(MAX_SAMPLES);
    expect(buffer.recycled).toHaveLength(EVICT_BLOCK - 1);
  });
});

describe('resetBuffer', () => {
  it('clears the trace, the anchor and the gap cursor', () => {
    const buffer = createBuffer();
    ingest(buffer, [{timestamp: 1_000, force: 10}, {timestamp: 1_400, force: 20}]);

    resetBuffer(buffer);

    expect(buffer.points).toHaveLength(0);
    expect(buffer.recycled).toHaveLength(0);
    expect(buffer.anchor).toBeNull();
    expect(buffer.lastX).toBeNull();
    expect(buffer.max).toBe(Number.NEGATIVE_INFINITY);
    expect(buffer.last).toBe(0);
    expect(buffer.hasValue).toBe(false);
  });
});
