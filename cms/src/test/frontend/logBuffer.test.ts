/**
 * The run log is capped so a long cyclic run cannot stall the view carrying Stop, and every line
 * keeps an id that stays its React key after older lines are evicted.
 */
import { describe, expect, it } from 'vitest';
import { appendLog, EMPTY_LOG, type LogBuffer } from '../../main/frontend/components/dashboard/logBuffer.js';

function fill(count: number, capacity: number): LogBuffer {
  let log = EMPTY_LOG;
  for (let i = 0; i < count; i++) {
    log = appendLog(log, `line ${i}`, capacity);
  }
  return log;
}

describe('appendLog', () => {
  it('keeps every line below the capacity', () => {
    const log = fill(3, 5);

    expect(log.lines.map(l => l.text)).toEqual(['line 0', 'line 1', 'line 2']);
    expect(log.hidden).toBe(0);
  });

  it('keeps the newest lines and counts the evicted ones', () => {
    const log = fill(8, 5);

    expect(log.lines.map(l => l.text)).toEqual(['line 3', 'line 4', 'line 5', 'line 6', 'line 7']);
    expect(log.hidden).toBe(3);
  });

  it('gives each line its position in the whole log as a stable id', () => {
    expect(fill(8, 5).lines.map(l => l.id)).toEqual([3, 4, 5, 6, 7]);
  });

  it('does not modify the buffer it was given', () => {
    const before = fill(5, 5);
    const snapshot = JSON.stringify(before);

    appendLog(before, 'next', 5);

    expect(JSON.stringify(before)).toBe(snapshot);
  });

  it('stays bounded over a long run', () => {
    const log = fill(60_000, 500);

    expect(log.lines).toHaveLength(500);
    expect(log.hidden).toBe(59_500);
    expect(log.lines[499].text).toBe('line 59999');
  });
});
