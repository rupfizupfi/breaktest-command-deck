# Safety and recovery — load-cell loss detection, safe stop, resume

Guarantee: from last good sample to drive-disable command < ~450 ms typical; motor stop is
**verified** (speed read-back), the incident is persisted, and resume is only offered when
scientifically defensible and operator-confirmed.

## Contents

- [Detection (in `MeasurementLoop`, woken by each batch)](#detection-in-measurementloop-woken-by-each-batch)
- [Safe stop (`MotorSafetyController`; the driver gates every call)](#safe-stop-motorsafetycontroller-the-driver-gates-every-call)
- [Gates — the numbers, and why they are configurable](#gates--the-numbers-and-why-they-are-configurable)
- [Recovery and resume](#recovery-and-resume)
- [State machine and persistence](#state-machine-and-persistence)
- [Operator flow](#operator-flow)
- [Edge cases the design covers](#edge-cases-the-design-covers)

The numbers are the owner's (2026-08-29); the failure trace behind the design is the 2026-08-16
safety audit, whose findings C5 and C6 are still open. Driver constraints, verified against the
rebuilt `dscusb` jar:

- **A stopped `CellValueStream` cannot be restarted** — reconnection must build a new instance.
- **A transient driver fault does not end the stream.** `dscusb` 0.3.0 drops it and reads on within
  an 80 ms budget, exposing the discard count through `LoadCellStream.droppedSampleCount()` (OQ-74 —
  [`driver-jars.md`](driver-jars.md#dscusbjar--load-cell)). A hole that outlasts that budget, or a
  terminal code, still arrives here as silence.
- `Connection.open()` fails inside the spawned thread, so a reconnect is judged **primarily by fresh
  data**; `isReading()` / `lastError()` are a **diagnosis, not a trigger** — escalation stays on the
  timeout, so a sensor that dies unexplained trips identically (`MeasurementLoop#describeSilence`).

## Detection (in `MeasurementLoop`, woken by each batch)

| Detector | Trigger | Default |
|---|---|---|
| No-data timeout (primary) | the loop's `poll(timeout)` runs out; the timeout is what is left of it since `lastDataNanos`, which is taken when a batch leaves the queue, before it is processed; arms after first measurement | 250 ms |
| Frozen value | N consecutive bit-identical samples (`floatToRawIntBits`) — live strain gauge always has LSB noise — or one value held for `maxFrozenMillis`, whichever comes first | 100 samples, 1000 ms |
| Plausibility | NaN/Inf, \|F\| > 1.5 × rated capacity, implausible **rises** (M-of-N vote). Fast force **drops** never trip — a real sample break must not be masked | 3 of 5 |
| Sample interval | a drain's time since the previous drain ÷ its sample count exceeds `maxSampleIntervalMillis` (M-of-N vote over drains); the first drain of a stream, fresh or reconnected, has nothing to time and casts no vote | 100 ms, 3 of 5 drains |

The sample-interval bound catches the feed the other three pass: one distinct plausible sample every
200 ms, never silent for 250 ms nor 100 identical, decides limits at 5 Hz; it trips on the third
sparse drain, 600 ms in. **It bounds density, not latency**: ten samples every 240 ms (24 ms each)
pass, rightly, as the no-data timeout bounds how long a decision waits. Its 100 ms default is
**uncalibrated against the bench** — ~11 × the bench's ~8.7 ms per sample
([`specimen-profiles.md`](specimen-profiles.md)), 20 × the simulator's 5 ms tick. Sparse drains are
counted per run and logged as `Sparse load cell drain` warnings (at most one a second; the count
covers the rest), the trip line carries the count, and each run ends with a `Load cell stream ended:
N sparse drain(s) this run` line beside the driver's discard count; that line reading zero over a
full bench run is the check still owed. No `SimulatedFault` produces a trickle. The frozen
detector's time bound catches what the floor admits and its count is slow on: 80 ms per sample takes
8 s to 100 repeats. It runs on sample time, each repeat credited its drain's share of the interval
as the sample-interval vote spreads it, so a loop stall's backlog is no held value. Its 1000 ms
never fires first at the bench's rate (the count trips at ~870 ms; 500 ms simulated) and is
**uncalibrated**: whether a quiet, unloaded cell holds one value that long is unmeasured.

Trip → on the watchdog thread: de-energize, flush the tail (bounded), open the gap, park, then
**`RunEvent.SensorLost`**; `RunLoop#accept` commits `SENSOR_LOST` on that thread (OQ-64 step 6).
`LoadCellDevice#isDataFlowing` / `awaitFreshMeasurement` serve checks, dashboard and resume. **The
plausibility verdict gates the envelope.** `minValue`/`maxValue` update only for a sample that
passed (`MeasurementLoop#inspectSample`), and `Limits` refuses a non-finite limit. Otherwise one NaN
(or a finite-but-absurd reading) poisons the cyclic programs' force limits and no PULL or RELEASE
fires again while the motor keeps driving.

## Safe stop (`MotorSafetyController`; the driver gates every call)

The deck holds no lock around a `Drive` call (BL-121): a gated plugin (`DRIVER_SERIALIZES`, device-api
1.5.0) sends a stop next after the one transaction in flight ([gate](device-api.md#the-call-gate-150)).
An ungated plugin is **refused at energize** and at every other non-stop write (`withDrive`,
`withDriveUnlessLatched`), with an `IllegalStateException` naming its jar and contract version as
for a pre-1.2.0 one; it can still be stopped and de-energized at boot, never run a test. Those stops
and their verification reads have **no bound** and go out unserialized beside the info poll: no
deadline exception exists on such a handle, so nothing below that cites one applies. The latch stays
the deck's policy; an **epoch fence** replaces the lock's atomicity: `energize`, `RunLoop#turn` and
`#runPhaseSpeed` read `Drive#stopEpoch()`, check the latch, then submit one `runLinked` group fenced
on it. A stop latches before it bumps the epoch, so it rejects that group unsent or cuts it short and
is the drive's last command. A zero rpm in a group is itself a STOP-lane write; no program sends one
(BL-156).

Three tiers; each verifies via `getMotorSpeedValueAsRpm() ≈ 0` (deadline:
`MotorSafetyController.VERIFY_DEADLINE_MS`). `SafeStopResult.Tier` records the tier reached and
decides whether resume is offered: `EXISTING_HANDLE` / `FRESH_HANDLE` name the tier that produced the
verdict, `OPERATOR_ESCALATION` that none verified a stop (tier 2 failed, or could not run: tier 1 was
wedged, or the old handle's transfer still held the port), `NONE` that the dispatch itself failed.

1. Existing handle: `Drive#stop()` — start and general enable cleared, reference 0, direction and
   ramp untouched: output stage off, the motor coasts (a ramp stop keeps loading for
   `stopRampSeconds`; blind reversal could slam through zero). One telegram on the gated CFW11
   driver; an older plugin runs the contract's fallback, one-write de-energize then zero reference,
   each going out whatever the other did. Verification re-sends the one-write de-energize every poll
   until the control word **reads back** general enable and start clear; a verified stop needs that
   read-back *and* standstill, and responsiveness — the gate to tier 2 — is judged by the read-back.
   A stop that misses its 1500 ms deadline (`DriveDeadlineException`) is **wedged**: nothing more is
   read and tier 3 follows directly, as a fresh handle beside the transaction in flight would be a
   second session (OQ-50); the queued stop still goes out once that transaction ends. Any other comms
   failure is a lossy link: verification runs, tier 2 may follow.
2. Fresh re-enumeration, all under the device's lifecycle lock and instance monitor
   (`FrequencyInverterDevice#runExclusive`), so no `connect()`, `disconnect()` or `reset()` lands
   inside: close the old handle, open, stop, verify, close, then `invalidateConnection` marks the
   handle lost (holders keep their references, OQ-68) and the next `connect()` re-opens. The old
   close waits at most 1500 ms ([device-api 1.6.0](device-api.md#the-call-gate-150)); with its
   transfer still in flight, `closeDriveHandle()` answers `PENDING`, **no fresh handle opens**, tier 2
   reports "not attempted", and the queued stops go out when the transfer ends. The port closes no
   later than the next `connect()`'s retry of that close: at the transfer's end where the I/O thread
   runs the port close (`GatedDrive`, the simulator), only in that retry for usbmodbus `Cfw11Drive`.
   The retry fails the connect with `DriveUnavailableException` while the port is held;
   `disconnect()` retries alike, and a fresh tier-2 handle's pending close is parked
   (`parkPendingClose`). `openConnection` closes any open handle first: no second session is ever
   stacked in software (OQ-50). Pending path: once tier 2 holds the lock, tier 3 within 1500 ms
   plus slack, however long the wedge (BL-148). **Getting the lock is bounded** (BL-212) at
   `RUN_EXCLUSIVE_WAIT_MS`, the 1500 ms close bound plus 1000 ms, interrupts included: a
   `disconnect()` already inside holds it up to ~5 s (the 2000 ms poll-thread join, then a pending
   close retried and the handle closed, 1500 ms each), a `connect()` or `reset()` for as long as
   `DriveProvider#open()` takes, which the contract does not bound, and `unregisterObserver()` for
   its poll-thread join. Past the bound tier 2 is "not attempted": nothing is closed, opened or
   marked lost, and tier 3 names the holder (operation, thread, held-for), so a stop whose tier 1
   failed at once reports within 3000 ms. That result alone is **not replayed**: the next `safeStop`
   of the run runs the ladder again, since the holder may be a `connect()` that leaves a working
   handle for tier 1. Accepted cost: behind a `disconnect()` paying its whole ~5 s, tier 3 comes
   before a fresh stop could have run; the armed link-loss stop is the backstop. A second stop
   caller behind another caller's tier 2 gets the same bounded tier 3, a false alarm. The first
   caller's result usually overwrites it; nothing orders the two writes when the first finishes
   right at the bound, and a false tier 3 written last is retryable, so the next `safeStop` runs
   the ladder again instead of replaying it. `MotorSafetyControllerTest` holds the uncontended and the contended
   bounds (`aTierTwoBehindAConnectWedgedInOpen…`, `…BehindADisconnectWedgedInClose…`,
   `aSecondStopCallerBehindAnotherTierTwo…`), `FrequencyInverterDeviceTest` and `DeviceTest` the
   lock; `RunScenarioTest` runs it end to end.
3. Backstops: the drive's own **link-loss stop** — load-bearing, keep it. Every energize group arms
   it first: the communication-error action (`Drive.COMMUNICATION_ERROR_DISABLE_VIA_GENERAL_ENABLE`),
   then the timeout (`Drive#enableLinkLossStop`, `deck.drive.link-loss-timeout`, bounds in
   `DriveLinkProperties`); a drive that cannot arm it is not energized. Silence past the timeout (JVM
   hang, USB loss, a wedged transaction) makes the drive remove general enable by itself. The gated
   driver that armed it feeds it: any telegram resets P0314, and it injects one read only after a
   third of the timeout without traffic, paused from a de-energizing write to the next arm
   (usbmodbus `LinkKeepAlive`, the simulator's `SimulatedLinkKeepAlive`); the deck sends no
   keep-alive. It never reacts to load-cell loss; whether the CFW11 applies it to USB is a bench
   check ([`driver-jars.md`](driver-jars.md#usbmodbusjar--frequency-inverter)).

**The bus stop is latency; P0313/P0314 and a hardwired channel are the guarantee** (a bus stop is
ordinary communication under IEC 61800-5-2's black channel). That channel — unused relay 2 of
`FourWayRelaySwitch` on the CFW11 general-enable/STO input — is **out of scope** (relay firmware
extension); tier 3 logs and escalates to the operator only.

## Gates — the numbers, and why they are configurable

Every detection, reconnect and resume threshold is a `RecoveryProperties` gate, in time rather than
sample counts and snapshotted per run: [`recovery-gates.md`](recovery-gates.md).

## Recovery and resume

`SensorReconnector` (own `sensor-reconnector` thread): `Device.reset()` (non-refcounted close +
reopen → new `CellValueStream`) → fresh measurement timestamped after the reset →
`MeasurementLoop#beginRecoveryGate`, which passes on an unbroken run of plausible samples, the drift
test above included, that both lasts `plausibilityGateMillis` and holds
`plausibilityGateMinSamples`: two samples one window apart do not pass. A sample of a sparse drain
breaks the run however plausible it is, and a stream too slow to reach the count before the gate's
deadline (2 × `plausibilityGateMillis` + 1 s) fails the gate. **Never auto-tare under load** — it
would zero real force and corrupt every later limit decision; a failed gate keeps `canResume=false`,
abort only. The wait for the verdict is bounded by the window plus one attempt's worst case, and run
teardown (`shutdownNow()`) cancels a queued attempt, so a wedged reset can leak the reconnector
thread but never the run's recovery worker or its verdict.

| Test type | Policy |
|---|---|
| Destructive | **Abort only** — blind coast-down voids the single-pull curve. Abort skips `SuckJob`, which is gated on a measured force-limit crossing |
| Cyclic | Resume iff the gates below all pass, plus **explicit operator confirm** — never auto-resume |
| TimeCyclic | Same gates, but resume **re-enters calibration** (`RestartCalibration`) on the calibration setup; a new `TimedCyclePhase` follows only once both legs are timed again |

`ResumeGates#allowResume()`, the Resume transition's guard under the state lock: resume enabled and
supported (`ResumePolicy#supportsResume()`) → this incident's gate passed → `lossCount ≤
maxLossesPerRun` → hold shorter than `maxHoldForResumeMillis` → **`isDriveAvailable()`**, the
decisive one: past tier 1 the CFW11 is handle-less, so **past `EXISTING_HANDLE`, resume is
abort-only by construction.** **There is no pausing a `TimedCyclePhase`.** `TimeCyclicProgram`
closes it at hold entry and **discards it**: a `ScheduledExecutorService` cannot be restarted after
`shutdownNow()`, so a paused phase would never fire again; the resume calibrates and builds a new
one.

Resume sequence, the `RESUMING` entry action on the run loop (`RunLoop#resume`, order load-bearing):
`resetEdges()` → `discardMotionEvents()` → **`awaitFreshMeasurement(2000)` && `isDataFlowing(250)`**
→ capture the incident's stop → `runWhileIn(RESUMING, clearStopLatch)` else stop, de-energized →
re-init the drive as in `setup()`, toward the nearer limit → `transition(RUNNING)` else undo the
energize with a `safeStop` → `endHold()` → gap `RESUMED`. The data check (it stands in for re-arming
the watchdog) is re-done as the link may have dropped since the gate. A Stop, Abort or loss leaves
`RESUMING` before it latches (I11); a throw ends the run `FAULT`. Other gap outcomes:
`RESUME_FAILED`, `SUPERSEDED` (the next loss came first), the terminal state of a run that ended
holding; `HOLDING` = died.

**Tier 3 is never claimed as a stop**: the motor may run until the drive's link-loss stop (P0314)
fires. All-tier failure ends the run `FAULT` with no resume path; the UI says to use the physical
E-stop, as does the log, a gap records `stopVerified=false`, and `TestStateMessage` has no tier
field, so that log line is the signal. The operator's Stop is never swallowed during a hold:
`OperatorStop` is an event type of its own, handled in every state, so nothing queued ahead of it
can stand in for it.

## State machine and persistence

`TestState` / `TestStateMachine`, one per run: `IDLE → STARTING → RUNNING → SENSOR_LOST → SAFE_HOLD
→ RESUMING|ABORTED`; `RUNNING → STOPPING → FINISHED`; anything → `ABORTED` or `FAULT`; `RESUMING →
SENSOR_LOST` for a link that flaps while the gate is still running. Terminal states have no
successors, so a repeated terminal transition is a no-op — the run's teardown runs twice by design.

Every transition is logged, broadcast, and persisted by `TestResultStatusPersister` to
`TestResult.runStatus` + `interruptionLog`. The log is a JSON **object**, not an array of
transitions: an array has nowhere to carry the applied `RecoveryGates`. `runStatus` gains
`COMPLETED_WITH_GAPS` so a resumed run can never be mistaken for a clean one. The document (`schema`
2) carries the gates, every transition so far, `gapCount`, and `droppedSampleCount` — readings the
driver discarded, the only trace an absorbed transient fault leaves. The count is per stream and
`Device.reset()` opens a new stream at zero: a `SENSOR_LOST` entry carries the dying stream's drops,
a `FINISHED` entry the last stream's. It never changes `runStatus`; a run-fraction cap is OQ-81. A
key added to `RecoveryGates` bumps neither this schema nor the gaps sidecar's: it is additive, and
an older document is told apart by its absence.

`StartupRecoveryRunner` (on `ApplicationReadyEvent`) marks orphaned non-terminal rows `ABORTED`, and
on every real-mode boot, orphans or not, de-energizes the drive with one `Drive#stop()` on a
**detached daemon thread** through `FrequencyInverterDevice`'s own refcounted handle - never a
private handle (H-13). `BootDeEnergizeGate` refuses a Start until it is done, so no run is de-energized mid-setup.
A drive reporting `LOCAL` through `Drive#readControlSource` (keypad control, owner decision) is left
as is with a `SKIPPED` warning, since an operator may be jogging it; `REMOTE` and `UNKNOWN` (a
pre-1.3.0 plugin, a failed read) de-energize. The read is one request ahead of the stop. Not `safeStop()`: `motorEnergized` is false on a fresh singleton. `ShutdownSafeStop`
(`SmartLifecycle`, top phase) `safeStop`s an open drive on context close, 15 s bound.

**The force CSV stays byte-identical** — no comment rows, no sentinels; external tools read it. Its
`TraceWriter` (own thread, bounded queue; full or failed → `PersistenceFailed` → `FAULT`) stays open
across a hold: one file, the gap an in-band epoch-millis jump. `GapRecorder` explains it in a
`<millis>_gaps.json` sidecar beside the `<millis>_force.csv`, written atomically and only once a gap
exists. `CSVStoreService.listCSVFilesForTestResult` is filtered to `*_force.csv`: `TestLogger`'s
`<millis>_test.log` shares the directory and would reach the Excel export's `Double.parseDouble`.

## Operator flow

Topic `/topic/test-state` (`TestStateBroadcaster`, payload `TestStateMessage`), re-broadcast every
2 s during incidents on the run loop's `Tick` so reconnecting browsers converge. `TestRunnerService`
has `resume()` / `abort()` returning a `ResumeResponse` whose refusals carry a reason; `status()`
returns state + `canResume` so a browser joining mid-incident rebuilds the banner without a STOMP
frame. UI: red incident banner with Resume (gated + confirm dialog) / Abort; client staleness
fallback (> 2 s without force frames → amber); `connectionState$` distinguishes "sensor lost" (red)
from "connection to machine lost" (amber). Safety never depends on the browser.

## Edge cases the design covers

Dead sensor at start (fresh-data gate, refcount fix); loss during ramp-up (general-enable-first);
flapping link (backoff, gate hysteresis, `maxLossesPerRun`); loss while in SAFE_HOLD or during
RESUMING (`RESUMING → SENSOR_LOST` edge); simultaneous inverter + cell loss (tier 2/3, then
`isDriveAvailable()` refuses resume); app restart mid-incident (`StartupRecoveryRunner`); operator
Stop during hold (`OperatorStop` is its own event, latched first); break at the same instant as
disconnect (both de-energize; no `SuckJob`); garbage after reconnect (drift gate, no tare); stale
crossings (state rule + `discardMotionEvents`); a loop a full queue behind (stop, `WatchdogFailed`).
Not covered (**OQ-81**): only *consecutive* driver faults are budgeted, so a cell failing alternate
frames runs to completion at half rate unseen by the watchdog; only the drop count shows it.
