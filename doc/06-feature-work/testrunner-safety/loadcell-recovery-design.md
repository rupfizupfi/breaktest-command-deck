# Design: load-cell disconnect detection, safe stop, and recovery

Guarantee: from last good sample to drive-disable command < ~450 ms typical; motor stop is
**verified** (speed read-back), the incident is persisted, and resume is only offered when
scientifically defensible and operator-confirmed.

## Contents

- [Detection (in `LoadCellThread`, on its existing 20 ms loop)](#detection-in-loadcellthread-on-its-existing-20-ms-loop)
- [Safe stop (`MotorSafetyController`, all Cfw11 access behind one lock)](#safe-stop-motorsafetycontroller-all-cfw11-access-behind-one-lock)
- [Gates — the numbers, and why they are configurable](#gates--the-numbers-and-why-they-are-configurable)
- [Recovery and resume](#recovery-and-resume)
- [State machine and persistence](#state-machine-and-persistence)
- [Operator flow](#operator-flow)
- [File-by-file changes](#file-by-file-changes)
- [Edge cases the design covers](#edge-cases-the-design-covers)

**Shipped.** Detection landed in phase 1, recovery in phase 3. Every number below is the owner's
(2026-08-29) — the invented ones this doc first carried are gone. The failure trace it was written
against is in [`audit-findings.md`](audit-findings.md); C5, C6 and C7 are still open there.

Driver constraints, re-verified against the rebuilt `dscusb` driver jar:

- **A stopped `CellValueStream` cannot be restarted** — reconnection must build a new instance.
  Unchanged, and the load-bearing one.
- **A transient driver fault no longer ends the stream.** `dscusb` 0.3.0 drops it and reads on
  within an 80 ms budget, exposing the discard count through `LoadCellStream.droppedSampleCount()`
  (OQ-74 — [`driver-jars.md`](../../03-backend/driver-jars.md#dscusbjar--load-cell)). A hole that
  outlasts that budget, or a terminal code, still arrives here as silence.
- `Connection.open()` failures still happen inside the spawned thread, so a reconnect is judged
  **primarily by fresh data**. `isReading()` / `getLastError()` are a **diagnosis, not a trigger** —
  escalation stays on the timeout, so a sensor that dies without explanation trips identically
  (`LoadCellThread#describeSilence`).

## Detection (in `LoadCellThread`, on its existing 20 ms loop)

| Detector | Trigger | Default |
|---|---|---|
| No-data timeout (primary) | `nanoTime() - lastDataNanos > timeout` in the empty-buffer branch; arms after first measurement | 250 ms |
| Frozen value | N consecutive bit-identical samples (`floatToRawIntBits`) — live strain gauge always has LSB noise | 100 samples |
| Plausibility | NaN/Inf, \|F\| > 1.5 × rated capacity, implausible **rises** (M-of-N vote). Fast force **drops** never trip — a real sample break must not be masked | 3 of 5 |

Trip → `SensorLossListener.onSensorLoss(reason, lastKnownForce)` **synchronously on the
watchdog thread**, bypassing the signal queue (the runner thread may be blocked in
`processSignals`). `LoadCellDevice` additionally exposes `isDataFlowing(maxAgeMs)` /
`awaitFreshMeasurement(timeoutMs)` for startup checks, the dashboard and the resume precondition.

`TestContext.SENSOR_LOST_SIGNAL = 3` exists but is **never enqueued**. Every consumer of that queue
*acts* on whatever it pops, so a bookkeeping-only value would be indistinguishable from a command;
the state machine carries the bookkeeping instead. The constant stays so the wire values remain
documented in one place.

**The plausibility verdict now gates the envelope.** `minValue`/`maxValue` update only for a sample
that passed (`LoadCellThread#inspectSample`), and `TestContext` refuses a non-finite limit outright.
Before that ordering existed, one NaN made `Math.min`/`Math.max` NaN, `CyclicTest` fed it into the
force limits, and every later comparison was false — no PULL or RELEASE ever fired again with the
motor still driving. A finite-but-absurd reading did the same damage arithmetically.

## Safe stop (`MotorSafetyController`, all Cfw11 access behind one lock)

Three tiers; every tier verifies via `getMotorSpeedValueAsRpm() ≈ 0` (deadline:
`MotorSafetyController.VERIFY_DEADLINE_MS`). The tier
reached is recorded as `SafeStopResult.Tier` — `EXISTING_HANDLE`, `FRESH_HANDLE`, `NONE` — and
decides whether resume is even offered.

1. Existing handle: `setGeneralEnable(false)` **first** (output stage off, coast — a ramp stop
   keeps loading for `stopRampSeconds`; blind reversal could slam through zero), then
   `setSpeedReferenceValueAsRpm(0)`, `setStart(false)`.
2. Fresh `Cfw11` re-enumeration (pattern of `TestRunnerThread#retryShutdownOnException`; OQ-50
   dual-instance caveat applies).
3. Backstops: the drive's own `setActionInCaseOfCommunicationError(2)` — load-bearing, keep it.
   The proposed relay kill line (unused relay 2 of `FourWayRelaySwitch` in series with the CFW11
   general-enable/STO input) stays **out of scope**: it needs a relay firmware extension. Until
   then tier 3 logs and escalates to the operator only.

All-tier failure → state `FAULT`, UI shows "use physical E-stop", no resume path exists.
`TimeCyclicTest` stops its `TimeProcessor` at hold entry and **discards it**.

## Gates — the numbers, and why they are configurable

The bench tracks at maximum resolution and pulls material apart, so *seconds* of missing data are
critical. Every gate is therefore seconds-scale, and expressed in **time, not sample counts** — the
sample rate is a driver and configuration fact, so a count would silently mean something different
after a `RATE` change.

`RecoveryProperties` (`@ConfigurationProperties("deck.testrunner.recovery")`, `@Validated`) holds
them; defaults live in the class, not in `application*.properties`.

| Property | Default | What it bounds |
|---|---|---|
| `reconnectWindowMillis` | 10 000 | Total time the reconnector retries before the loss is unrecoverable |
| `backoffMillis` | 1000, 2000, 4000 | Delay before each attempt; last entry repeats |
| `safeHoldTimeoutMillis` | 60 000 | Hold the server auto-aborts, with no UI involvement |
| `maxHoldForResumeMillis` | 30 000 | Past this the specimen has crept — resume refused, hold still alive |
| `plausibilityGateMillis` | 100 | Continuous plausible samples a reconnected stream must deliver |
| `driftFraction` | 0.02 | \|F − lastKnownForce\| against 2 % of the force envelope |
| `minEnvelopeNewton` | 1000 | Floor for that envelope, so near-zero limits don't make the gate unpassable |
| `maxLossesPerRun` | 2 | The next loss aborts instead of offering another recovery |

Two cross-field `@AssertTrue` checks refuse a configuration that reads as working and is not: a
resume window longer than the hold (Resume offered for a run already auto-aborted), and a first
backoff at or beyond the reconnect window (logged as "reconnect failed" for a sensor nobody retried).

**The applied values are snapshotted per run** (`RecoveryGates`, an immutable record taken at start)
and copied verbatim into both the `interruptionLog` and the gaps sidecar. A gate that is a
deployment fact makes a run unauditable unless the run records which value it actually used.

## Recovery and resume

`SensorReconnector` (own `sensor-reconnector` thread): `Device.reset()` (non-refcounted close +
reopen → new `CellValueStream`) → fresh measurement timestamped after the reset →
`LoadCellThread#beginRecoveryGate` for `plausibilityGateMillis` of continuous plausible samples,
including the drift test above. **Never auto-tare under load** — it would zero out real force and
corrupt every later limit decision; a failed gate keeps `canResume=false`, abort only.

| Test type | Policy |
|---|---|
| Destructive | **Abort only** — blind coast-down voids the single-pull curve. Abort skips `SuckJob`, which is now gated on a measured force-limit crossing rather than on any signal |
| Cyclic | Resume iff the gates below all pass, plus **explicit operator confirm** — never auto-resume |
| TimeCyclic | Same gates, but resume **re-enters the analyse phase** with a new `TimeProcessor` |

`AbstractTest#canResume()`, in order: resume enabled and the test type supports it → last gate
passed → `lossCount ≤ maxLossesPerRun` → hold shorter than `maxHoldForResumeMillis` →
**`MotorSafetyController#isDriveAvailable()`**.

That last one is the gate that matters and the one this doc originally omitted. Anything past tier 1
leaves the CFW11 deliberately handle-less with its refcount already dropped, so there is nothing to
re-energize through: **past `EXISTING_HANDLE`, resume is abort-only by construction.**

**There is deliberately no `TimeProcessor.pause()`.** A `ScheduledExecutorService` cannot be
restarted after `shutdownNow()`, so "pause and resume the same processor" is not expressible, and a
`pause()` pretending otherwise would hand back a processor that silently never fires again.
`TimeProcessor.start()` was dead code and has been deleted.

Resume sequence, on the run-control thread (`TestRunnerThread#performResume`, order load-bearing):
`resetSignalDedup()` → drain signals queued during the hold while dispatch is still disabled →
**`awaitFreshMeasurement(2000)` && `isDataFlowing(250)`** → re-init the drive as in `setup()`,
initial direction toward the nearer limit. The data check replaces the older "re-arm the watchdog
before drive re-enable", which was not satisfiable as stated: the no-data detector cannot arm
without data. It is re-checked here rather than trusted from the reconnector's gate milliseconds
earlier on another thread, because the link may have dropped again in between.

The operator's Stop is never swallowed during a hold: signal `0` is exempt from both the
`sendSignal` dedup and the dispatch gate, so it reaches the queue even when it repeats a stop the
watchdog already sent.

## State machine and persistence

`TestState` / `TestStateMachine`, one per run: `IDLE → STARTING → RUNNING → SENSOR_LOST → SAFE_HOLD
→ RESUMING|ABORTED`; `RUNNING → STOPPING → FINISHED`; anything → `ABORTED` or `FAULT`;
`RESUMING → SENSOR_LOST` for a link that flaps while the gate is still running. Terminal states have
no successors, which makes a repeated terminal transition a no-op — `AbstractTest.cleanup()` runs
twice by design.

Every transition is logged, broadcast, and persisted by `TestResultStatusPersister` to
`TestResult.runStatus` + `interruptionLog`. The log is a JSON **object**, not an array of
transitions: an array has nowhere to carry the applied `RecoveryGates`. `runStatus` gains
`COMPLETED_WITH_GAPS` so a resumed run can never be mistaken for a clean one.

`StartupRecoveryRunner` (`ApplicationRunner`) marks orphaned non-terminal rows `ABORTED` on boot and,
only when such rows exist and hardware mode is real, issues a defensive de-energize on a **detached
daemon thread** with a handle it opens itself. Deliberately *not*
`MotorSafetyController.safeStop()`: that keys its decision on `motorEnergized`, false on a singleton
this process just constructed, so it would classify a boot stop as "nothing to stop" and touch no
hardware at all.

**The force CSV stays byte-identical** — no comment rows, no sentinels; external tools read it. The
writer stays open across a hold so the trace is one continuous file, and the gap is visible in-band
as an epoch-millis discontinuity. `GapRecorder` explains it out-of-band in a `<millis>_gaps.json`
sidecar beside the `<millis>_force.csv`, written atomically and only once a gap exists.
`CSVStoreService.listCSVFilesForTestResult` is filtered to `*_force.csv`, which also fixed a
pre-existing bug: `TestLogger`'s `<millis>_test.log` shares that directory and was being fed to the
Excel export's `Double.parseDouble`.

## Operator flow

Topic `/topic/test-state` (`TestStateBroadcaster`, payload `TestStateMessage`), re-broadcast every
2 s during incidents so reconnecting browsers converge. `TestRunnerService` gains `resume()` /
`abort()` returning a `ResumeResponse` whose refusals carry a reason; `status()` returns state +
`canResume` so a browser joining mid-incident rebuilds the banner without a STOMP frame.
UI: red incident banner with Resume (gated + confirm dialog) / Abort; client staleness fallback
(> 2 s without force frames → amber); `connectionState$` distinguishes "sensor lost" (red)
from "connection to machine lost" (amber). Safety never depends on the browser.

## File-by-file changes

| File | Change |
|---|---|
| `testrunner/TestState.java`, `TestStateMachine.java` | Enum + synchronized transition map with listeners |
| `testrunner/RecoveryProperties.java`, `RecoveryGates.java` | Configurable gates + the immutable per-run snapshot |
| `testrunner/MotorSafetyController.java` | Locked `withDrive()` + 3-tier verified `safeStop()`; `clearStopLatch()` serialized under a latch lock and refused while a stop is executing |
| `testrunner/SensorLossListener.java`, `SensorReconnector.java` | Callback interface; backoff reconnect + plausibility gate |
| `testrunner/LoadCellThread.java` | Detectors, `beginRecoveryGate()`, envelope gated on plausibility, CSV writer held open across a hold |
| `testrunner/TestContext.java` | `SENSOR_LOST_SIGNAL` (never sent), `resetSignalDedup()`, signal-0 exemption, non-finite limits refused |
| `testrunner/GapRecorder.java`, `TestResultStatusPersister.java` | The gaps sidecar and the run's audit record |
| `device/Device.java` | `reset()` — non-refcounted reopen |
| `device/loadcell/LoadCellDevice.java` | `lastDataNanos`, `isDataFlowing()`, `awaitFreshMeasurement()`, `getStreamFailure()`, `droppedSampleCount()`; bounded reader join; reader pinned to the stream it captured |
| `testrunner/AbstractTest.java` | Implements `SensorLossListener`; owns controller/state machine/reconnector; `canResume()` |
| `testrunner/DestructiveTest.java` | Abort-only on sensor loss; `SuckJob` gated on a measured crossing and skipped on abort |
| `testrunner/CyclicTest.java` | `supportsResume()`, `reinitDriveForResume()`; ignore stale signals while ≠ RUNNING |
| `testrunner/TimeCyclicTest.java`, `cyclic/TimeProcessor.java` | Processor discarded at hold entry, rebuilt on resume; dead `start()` deleted |
| `testrunner/TestRunnerThread.java` | Owns state machine + broadcast/persist listener; `performResume()`/`abortTest()`; SAFE_HOLD timeout |
| `testrunner/StartupRecoveryRunner.java` | Orphan-row abort + defensive stop on boot |
| `cms .../data/TestResult.java`, `RunStatus.java` | `runStatus`, `interruptionLog`; repository finder |
| `cms .../filesystem/CSVStoreService.java` | `TestRunFiles`, and the `*_force.csv` listing filter |
| `api/services/TestRunnerService.java` | `resume()`, `abort()`, extended `status()` |
| `frontend/service/StatusService.ts` | `testStateObservable`, `connectionState$` |
| `frontend/components/dashboard/LiveTestResult.tsx` | Incident banner, Resume/Abort, staleness fallback |

## Edge cases the design covers

Dead sensor at start (fresh-data gate, refcount fix); loss during ramp-up (general-enable-first);
flapping link (backoff, gate hysteresis, `maxLossesPerRun`); loss while in SAFE_HOLD or during
RESUMING (`RESUMING → SENSOR_LOST` edge); simultaneous converter + cell loss (tier 2/3, then
`isDriveAvailable()` refuses resume); app restart mid-incident (`StartupRecoveryRunner`); operator
Stop during hold (signal-0 exemption, serialized via `withDrive`); break at the same instant as
disconnect (indistinguishable — both de-energize; no `SuckJob`); garbage after reconnect (drift
gate, no tare); stale pre-loss queued signals (dispatch gate + drain).

Not covered, and tracked as **OQ-81**: only *consecutive* driver faults are budgeted, so a cell
failing alternate frames indefinitely runs to completion at half sample rate with the watchdog never
firing. The drop count is the only signal.
