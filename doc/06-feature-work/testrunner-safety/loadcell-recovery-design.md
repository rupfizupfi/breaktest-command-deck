# Design: load-cell disconnect detection, safe stop, and recovery

Guarantee: from last good sample to drive-disable command < ~450 ms typical; motor stop is
**verified** (speed read-back), the incident is persisted, and resume is only offered when
scientifically defensible and operator-confirmed.

## Contents

- [Detection (in `LoadCellThread`, on its existing 20 ms loop)](#detection-in-loadcellthread-on-its-existing-20-ms-loop)
- [Safe stop (`MotorSafetyController`, all Cfw11 access behind one lock)](#safe-stop-motorsafetycontroller-all-cfw11-access-behind-one-lock)
- [Recovery and resume](#recovery-and-resume)
- [State machine and persistence](#state-machine-and-persistence)
- [Operator flow](#operator-flow)
- [File-by-file changes](#file-by-file-changes)
- [Edge cases the design covers](#edge-cases-the-design-covers)

Detection landed in phase 1; what remains is recovery. The failure trace this was written against
is in [`audit-findings.md`](audit-findings.md) — C5, C6 and C7 are still open there; C1–C4 were
closed by phase 1 and the driver rebuild, and git history holds their text.

Driver constraints, re-verified against the rebuilt `lib/dscusb.jar` (`ec47aa6`):

- **A stopped `CellValueStream` cannot be restarted** — reconnection must build a new instance.
  Unchanged, and the load-bearing one.
- `Connection.open()` failures still happen inside the spawned thread, so a reconnect is still
  judged **primarily by fresh data**. What changed: the thread no longer dies unhookably.
  `isReading()` and `getLastError()` now expose why it stopped, and `LoadCellDevice#getStreamFailure`
  turns that into a named cause. It is a **diagnosis, not a trigger** — escalation stays on the
  timeout, so a sensor that dies without explanation trips identically
  (`LoadCellThread#describeSilence`).

## Detection (in `LoadCellThread`, on its existing 20 ms loop)

| Detector | Trigger | Default |
|---|---|---|
| No-data timeout (primary) | `nanoTime() - lastDataNanos > timeout` in the empty-buffer branch; arms after first measurement | 250 ms |
| Frozen value | N consecutive bit-identical samples (`floatToRawIntBits`) — live strain gauge always has LSB noise | 100 samples |
| Plausibility | NaN/Inf, \|F\| > 1.5 × rated capacity, implausible **rises** (M-of-N vote). Fast force **drops** never trip — a real sample break must not be masked | 3 of 5 |

Trip → `SensorLossListener.onSensorLoss(reason, lastKnownForce)` **synchronously on the
watchdog thread**, bypassing the signal queue (the runner thread may be blocked in
`processSignals`). A new `TestContext.SENSOR_LOST_SIGNAL = 3` is enqueued afterwards for
bookkeeping only. `LoadCellDevice` additionally exposes `isDataFlowing(maxAgeMs)` /
`awaitFreshMeasurement(timeoutMs)` for startup checks and the dashboard.

## Safe stop (`MotorSafetyController`, all Cfw11 access behind one lock)

Three tiers; every tier verifies via `getMotorSpeedValueAsRpm() ≈ 0` (2 s deadline):

1. Existing handle: `setGeneralEnable(false)` **first** (output stage off, coast — a ramp stop
   keeps loading for `stopRampSeconds`; blind reversal could slam through zero), then
   `setSpeedReferenceValueAsRpm(0)`, `setStart(false)`.
2. Fresh `Cfw11` re-enumeration (pattern of `TestRunnerThread#retryShutdownOnException`; OQ-50
   dual-instance caveat applies).
3. Backstops: the drive's own `setActionInCaseOfCommunicationError(2)` — load-bearing, keep it —
   plus proposed hardware task: wire unused relay 2 of `FourWayRelaySwitch` in series with the
   CFW11 general-enable/STO input as a software-independent kill line (needs relay firmware
   extension; until then tier 3 logs only).

All-tier failure → state `FAULT`, UI shows "use physical E-stop", no resume path exists.
`TimeCyclicTest` additionally pauses `TimeProcessor` immediately (`shutdownNow`, not
`shutdown` — pending scheduled signals must not fire during the hold).

## Recovery and resume

`SensorReconnector` (single scheduled thread, backoff 1/2/5 s, window = SAFE_HOLD timeout):
`Device.reset()` (non-refcounted close+reopen → new `CellValueStream`) → fresh measurement with
timestamp after reset → 25-sample plausibility gate incl. `|F − lastKnownForce| <` 10 % of test
envelope. **Never auto-tare under load** — it would zero out real force and corrupt every later
limit decision; implausible reconnect keeps `canResume=false`, abort only.

| Test type | Policy |
|---|---|
| Destructive | **Abort only** — blind coast-down voids the single-pull curve. Abort skips `SuckJob` (break unconfirmed) |
| Cyclic | Resume iff: gate passed, hold < 15 min (creep bound), force inside envelope, ≤ 3 losses per run, **explicit operator confirm** — never auto-resume |
| TimeCyclic | Same gates, but resume **re-enters the analyse phase** — relaxation during hold invalidates the time calibration |

Resume sequence (order matters): `TestContext.resetSignalDedup()` (else the `lastSendSignal`
dedup swallows the first post-resume signal → stall with motor running) → discard stale queued
PULL/RELEASE while state ≠ RUNNING → re-arm watchdog **before** drive re-enable → re-init drive
as in `setup()`, initial direction toward the nearer limit.

## State machine and persistence

`IDLE → STARTING → RUNNING → SENSOR_LOST → SAFE_HOLD → RESUMING|ABORTED`; `RUNNING → STOPPING →
FINISHED`; anything → `FAULT`. Terminal: `FINISHED`, `ABORTED`, `FAULT`. Owned by
`TestRunnerThread`; every transition is logged, broadcast, and persisted to new
`TestResult.runStatus` + `interruptionLog` (JSON array of transitions) — also closes
audit-findings H10. `StartupRecoveryRunner` (`ApplicationRunner`) marks orphaned non-terminal
rows `ABORTED` on boot and, only when such rows exist, issues a defensive fresh-Cfw11 stop
(the pre-crash JVM may have died mid-safe-stop). SAFE_HOLD auto-aborts after a configurable
timeout (default 10 min) — server-side, no UI required.

## Operator flow

New topic `/topic/test-state` (`TestStateMessage`: state, reason, canResume, attempt,
lastKnownForce, safeHoldDeadline), re-broadcast every 2 s during incidents so reconnecting
browsers converge. `TestRunnerService` gains `resume()` / `abort()`; `status()` returns state +
canResume so a browser joining mid-incident rebuilds the banner without a STOMP frame.
UI: red incident banner with Resume (gated + confirm dialog) / Abort; client staleness fallback
(> 2 s without force frames → amber); `connectionState$` distinguishes "sensor lost" (red)
from "connection to machine lost" (amber). Safety never depends on the browser.

## File-by-file changes

*(new)* means still to be created; **landed** marks what phase 1 already shipped.

| File | Change |
|---|---|
| `testrunner/TestState.java`, `TestStateMachine.java` *(new)* | Enum + synchronized transition map with listeners |
| `testrunner/MotorSafetyController.java` — **landed** | Locked `withDrive()` + 3-tier verified `safeStop()` |
| `testrunner/SensorLossListener.java`, `SensorReconnector.java` *(new)* | Callback interface; backoff reconnect + plausibility gate |
| `testrunner/LoadCellThread.java` — detectors **landed** | Still owed: `getLastForce()`, `markSensorRecovered()`, keeping the CSV writer open across a hold |
| `testrunner/TestContext.java` | `SENSOR_LOST_SIGNAL`, `resetSignalDedup()` |
| `device/Device.java` — refcount fix **landed** | Still owed: `reset()` (non-refcounted reopen). `markConnectionLost()` shipped carrying the OQ-68 defect |
| `device/loadcell/LoadCellDevice.java` — **landed** | `lastDataNanos`, `isDataFlowing()`, `awaitFreshMeasurement()`, plus `getStreamFailure()` for the driver's own cause |
| `testrunner/AbstractTest.java` | Implements `SensorLossListener`; owns controller/state machine/reconnector; `awaitLoadCellOrFail()` gates motor energization on first data; `cleanup()` via `withDrive` |
| `testrunner/DestructiveTest.java` | Abort-only on sensor loss; skip `SuckJob` on that path; `awaitLoadCellOrFail()` before energize |
| `testrunner/CyclicTest.java` | `supportsResume()`, `reinitDriveForResume()`; ignore stale signals while ≠ RUNNING |
| `testrunner/TimeCyclicTest.java`, `cyclic/TimeProcessor.java` | `pause()` (shutdownNow + unlisten); resume re-runs analyse phase |
| `testrunner/TestRunnerThread.java` | Owns state machine + broadcast/persist listener; `resumeTest()`/`abortTest()`; SAFE_HOLD timeout; fix H1/H2 |
| `testrunner/startup/check/LoadCellCheck.java` — **landed** | Demands a fresh measurement before any test. The CFW11 equivalent is still owed (OQ-44) |
| `testrunner/StartupRecoveryRunner.java` *(new)* | Orphan-row abort + defensive stop on boot |
| `cms .../data/TestResult.java` | `runStatus`, `interruptionLog`; repository finder |
| `api/services/TestRunnerService.java` | `resume()`, `abort()`, extended `status()` |
| `device/relayswitch/FourWayRelaySwitch.java` | Kill-line channel (paired hardware task) |
| `frontend/service/StatusService.ts` | `testStateObservable`, `connectionState$` |
| `frontend/components/dashboard/LiveTestResult.tsx` | Incident banner, Resume/Abort, staleness fallback |

## Edge cases the design covers

Dead sensor at start (fresh-data gate, refcount fix); loss during ramp-up (general-enable-first);
flapping link (backoff, 25-sample hysteresis, ≤ 3 losses/run); loss while in SAFE_HOLD or during
RESUMING (watchdog re-armed before drive enable); simultaneous converter + cell loss (tier 2/3);
app restart mid-incident (`StartupRecoveryRunner`); operator Stop during hold (NPE fix, serialized
via `withDrive`); break at the same instant as disconnect (indistinguishable — both de-energize;
no `SuckJob`); garbage after reconnect (gate, no tare); stale pre-loss queued signals (state guard
in `handleSignal`).
