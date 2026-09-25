/**
 * Pure-logic tests for the force-CSV parser (node environment — no browser, no jsdom).
 *
 * The chart's point count must equal the CSV's numeric row count: the force column is a Java float
 * written by Float.toString, so E-notation is a routine near-zero reading and dropping it would
 * silently shorten the trace and skew Max/Min.
 */
import { describe, expect, it } from 'vitest';
import { parseForceCsv } from '../../main/frontend/components/dashboard/forceCsv.js';

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
