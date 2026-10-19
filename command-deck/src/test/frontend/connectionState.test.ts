/**
 * The rules that decide which single warning the run view shows, and when the shared socket opens
 * and closes. StatusService only wires these to STOMP.
 */
import { describe, expect, it } from 'vitest';
import {
  ConsumerRefCount,
  deriveConnectionState,
  feedStatus,
  NEVER_RECEIVED,
  parseTestStateFrame,
  shouldApplyTestState,
  type TestStateFrame,
} from '../../main/frontend/service/connectionState.js';

function frame(overrides: Partial<Omit<TestStateFrame, 'state'>> & {state?: string} = {}): TestStateFrame {
  const {state = 'RUNNING', ...rest} = overrides;
  return {
    state: state as TestStateFrame['state'],
    reason: null,
    canResume: false,
    lossCount: 0,
    reconnectAttempt: 0,
    lastKnownForce: null,
    safeHoldDeadlineMillis: null,
    testResultId: 1,
    sequence: 0,
    ...rest,
  };
}

const FRESH = feedStatus(1_000, 1_500, 1_500);
const STALE = feedStatus(1_000, 1_500, 4_200);

describe('feedStatus', () => {
  it('is never-received without a frame', () => {
    expect(feedStatus(null, 1_500, 10_000)).toEqual(NEVER_RECEIVED);
  });

  it('is fresh up to and including the deadline', () => {
    expect(feedStatus(1_000, 1_500, 2_500)).toEqual({everReceived: true, stale: false, staleForSeconds: null});
  });

  it('is stale past the deadline, counting whole seconds since the frame', () => {
    expect(STALE).toEqual({everReceived: true, stale: true, staleForSeconds: 3});
  });
});

describe('deriveConnectionState', () => {
  it('ranks a sensor loss above a lost socket and a stale feed', () => {
    const lost = frame({state: 'SENSOR_LOST', reason: 'cable', canResume: true, safeHoldDeadlineMillis: 9});

    expect(deriveConnectionState(lost, false, STALE))
      .toEqual({kind: 'sensor-lost', reason: 'cable', canResume: true, deadlineMillis: 9});
  });

  it('treats SAFE_HOLD and RESUMING as the same incident', () => {
    expect(deriveConnectionState(frame({state: 'SAFE_HOLD'}), true, FRESH).kind).toBe('sensor-lost');
    expect(deriveConnectionState(frame({state: 'RESUMING'}), true, FRESH).kind).toBe('sensor-lost');
  });

  it('gives a sensor loss without a reason a default one', () => {
    const state = deriveConnectionState(frame({state: 'SENSOR_LOST'}), true, FRESH);

    expect(state).toMatchObject({kind: 'sensor-lost', reason: 'the load cell stopped delivering measurements'});
  });

  it('ranks a lost socket above a stale feed', () => {
    expect(deriveConnectionState(frame(), false, STALE)).toEqual({kind: 'server-lost'});
    expect(deriveConnectionState(null, false, FRESH)).toEqual({kind: 'server-lost'});
  });

  it('reports a stale feed on an open socket', () => {
    expect(deriveConnectionState(frame(), true, STALE)).toEqual({kind: 'feed-stale', seconds: 3});
  });

  it('is ok on an open socket with a fresh feed and no incident', () => {
    expect(deriveConnectionState(frame(), true, FRESH)).toEqual({kind: 'ok'});
    expect(deriveConnectionState(null, true, NEVER_RECEIVED)).toEqual({kind: 'ok'});
  });
});

describe('shouldApplyTestState', () => {
  it('drops a frame older than the one applied for the same run', () => {
    expect(shouldApplyTestState(frame({sequence: 5}), frame({sequence: 4}))).toBe(false);
  });

  it('applies an equal or newer sequence for the same run', () => {
    expect(shouldApplyTestState(frame({sequence: 5}), frame({sequence: 5}))).toBe(true);
    expect(shouldApplyTestState(frame({sequence: 5}), frame({sequence: 6}))).toBe(true);
  });

  it('applies a new run whose sequence restarted at 0', () => {
    expect(shouldApplyTestState(frame({testResultId: 1, sequence: 40}), frame({testResultId: 2, sequence: 0}))).toBe(true);
  });

  it('always applies "no run", and anything onto nothing', () => {
    expect(shouldApplyTestState(frame({sequence: 40}), null)).toBe(true);
    expect(shouldApplyTestState(null, frame())).toBe(true);
  });
});

describe('parseTestStateFrame', () => {
  it("fills absent fields with a fresh run's values", () => {
    expect(parseTestStateFrame('{"state":"RUNNING","testResultId":7}')).toEqual(frame({testResultId: 7}));
  });

  it('accepts canResume only when it is literally true', () => {
    expect(parseTestStateFrame('{"state":"SAFE_HOLD","canResume":"yes"}').canResume).toBe(false);
  });

  it('throws on a body that is not JSON', () => {
    expect(() => parseTestStateFrame('{"state":')).toThrow();
  });
});

describe('ConsumerRefCount', () => {
  it('opens for the first consumer and closes only after the last one leaves', () => {
    const consumers = new ConsumerRefCount();
    const infoBoard = {}, deckView = {};

    expect(consumers.acquire(infoBoard)).toBe(true);
    expect(consumers.acquire(deckView)).toBe(false);
    expect(consumers.release(deckView)).toBe(false);
    expect(consumers.release(infoBoard)).toBe(true);
  });

  it('counts a key acquired twice twice', () => {
    const consumers = new ConsumerRefCount();
    const view = {};

    consumers.acquire(view);
    consumers.acquire(view);

    expect(consumers.release(view)).toBe(false);
    expect(consumers.release(view)).toBe(true);
  });

  it('ignores a release for a key that never acquired', () => {
    const consumers = new ConsumerRefCount();
    consumers.acquire({});

    expect(consumers.release({})).toBe(false);
    expect(consumers.size).toBe(1);
  });
});
