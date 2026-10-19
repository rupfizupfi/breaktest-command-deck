/**
 * The chart's point count must equal the CSV's numeric row count: Float.toString writes near-zero
 * forces in E-notation, and dropping them would shorten the trace and skew Max/Min.
 */
import { describe, expect, it } from 'vitest';
import { parseForceCsv, toForceTrace } from '../../main/frontend/components/dashboard/forceCsv.js';

describe('parseForceCsv', () => {
  it('keeps E-notation forces with their numeric value', () => {
    const [timestamps, forces] = parseForceCsv('1700000000000,5.0E-4\n1700000000100,1.0E7\n');

    expect(timestamps).toEqual([1700000000000, 1700000000100]);
    expect(forces).toEqual([0.0005, 10000000]);
  });

  it('skips a header row and any other non-numeric row', () => {
    const [timestamps, forces] = parseForceCsv('timestamp,force\n1700000000000,12.5\n,\nnope\n');

    expect(timestamps).toEqual([1700000000000]);
    expect(forces).toEqual([12.5]);
  });

  it('parses CRLF and LF input identically', () => {
    const rows = '1700000000000,12.5\n1700000000100,-3.25\n';

    expect(parseForceCsv(rows.replace(/\n/g, '\r\n'))).toEqual(parseForceCsv(rows));
  });

  it('ignores a trailing empty line', () => {
    const [timestamps, forces] = parseForceCsv('1700000000000,12.5\n');

    expect(timestamps).toHaveLength(1);
    expect(forces).toHaveLength(1);
  });
});

describe('toForceTrace', () => {
  it('is null for a file without a sample', () => {
    expect(toForceTrace(parseForceCsv(''))).toBeNull();
    expect(toForceTrace(parseForceCsv('timestamp,force\n'))).toBeNull();
  });

  it('rebases time to the first sample and finds min, max and duration', () => {
    expect(toForceTrace([[1_000, 1_100, 1_250], [5, -2, 9]]))
      .toEqual({elapsedMillis: [0, 100, 250], forces: [5, -2, 9], minForce: -2, maxForce: 9, durationMillis: 250});
  });

  it('handles a single sample', () => {
    expect(toForceTrace([[1_000], [3]])).toMatchObject({minForce: 3, maxForce: 3, durationMillis: 0});
  });

  // Spreading this many values into Math.max throws RangeError.
  it('summarizes a 200k-sample run without overflowing the stack', () => {
    const count = 200_000;
    const timestamps = Array.from({length: count}, (_, i) => 1_700_000_000_000 + i * 10);
    const forces = Array.from({length: count}, (_, i) => Math.sin(i / 1_000) * 1_000);
    forces[123_456] = 150_000;
    forces[7] = -42_000;

    const trace = toForceTrace([timestamps, forces])!;

    expect(trace.maxForce).toBe(150_000);
    expect(trace.minForce).toBe(-42_000);
    expect(trace.durationMillis).toBe((count - 1) * 10);
    expect(trace.elapsedMillis).toHaveLength(count);
  });
});
