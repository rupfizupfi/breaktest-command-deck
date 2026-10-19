# Test-runner architecture — packages, one run, the structure lock

How the hardware and test-runner layer is put together: which package owns what, how one run
flows through it, the rules `ArchitectureTest` locks, and the safety invariants the code cites by
id. The lifecycle and the three programs are in
[`test-execution-engine.md`](test-execution-engine.md) and [`test-types.md`](test-types.md);
device internals in [`hardware-integration.md`](hardware-integration.md); the detectors, stop
tiers and resume in [`safety-and-recovery.md`](safety-and-recovery.md). Why the structure looks
like this, and what was rejected: [`../decisions.md`](../../decisions.md#test-runner-redesign-oq-64-owner-2026-10-12).

## Contents

- [Packages and who owns what](#packages-and-who-owns-what)
- [The dependency rule](#the-dependency-rule)
- [One run, end to end](#one-run-end-to-end)
- [The structure lock — `ArchitectureTest`](#the-structure-lock--architecturetest)
- [Constraints the structure keeps](#constraints-the-structure-keeps)
- [A full inbound queue is a dead loop](#a-full-inbound-queue-is-a-dead-loop)
- [Safety invariant register](#safety-invariant-register)
- [Still owed](#still-owed)

## Packages and who owns what

All under `command-deck/src/main/java/ch/rupfizupfi/deck/`. Dependencies point down.

| Package | Types | Owns |
|---|---|---|
| `api/services` | `TestRunnerService` | the Hilla entry; one `TestRunnerThread` |
| `testrunner` (composition) | `TestRunnerThread`, `TestRunnerFactory`, `RunDevices` | run scope, operator commands (commit under the state lock, enqueue), wiring the loop's seams |
| `testrunner/run` | `RunLoop`, `RunEvent`, `RunControl`, `ResumeGates`, `Verdict`, `FinishCause`, `Limit`, `Direction` | the run's one control thread: every entry action, program call and per-run field |
| `testrunner/program` | `TestProgram`, `TestProgramRegistry`, three programs + factories, `DriveSetup`, `Limits`, `Ramp`, `CyclicTurns`, `Phase`, `CalibrationPhase`, `TimedCyclePhase`, `ResumePolicy` | what a type declares; loop-thread only, no lock, no `volatile` |
| `testrunner/measure` | `MeasurementLoop`, `LimitEdgeDetector`, `TraceWriter`, `SensorReconnector`, `ForceLimits`, `EmergencyStop`, `RunEventSink` | the force watchdog, the CSV writer thread, the reconnect worker |
| `testrunner` (below) | `MotorSafetyController`, `TestStateMachine`, `SafeStopResult`, `RecoveryGates`, `GapRecorder`, boot/shutdown beans | the verified stop, the state machine, the audit records |
| `device` | `DeviceService`, `LoadCellDevice`, `FrequencyInverterDevice`, `Device` | device lifecycle; never sees `testrunner` |

## The dependency rule

Dependencies point **down only**:

```
api/                 TestRunnerService (Hilla)
testrunner/          TestRunnerThread, TestRunnerFactory, RunDevices, TestStateBroadcaster (composition)
testrunner/run/      RunLoop, RunEvent, RunControl, ResumeGates, Verdict
testrunner/program/  TestProgram, TestProgramRegistry, DriveSetup, ResumePolicy, phases
testrunner/measure/  MeasurementLoop, LimitEdgeDetector, TraceWriter, SensorReconnector
testrunner/          MotorSafetyController, TestStateMachine, boot/shutdown beans
device/              FrequencyInverterDevice, LoadCellDevice, Device
device-api/          the contract, plus its test fixtures
```

Two interfaces keep `measure` from pointing up: `EmergencyStop` (one method, implemented by
`MotorSafetyController`) and `RunEventSink` (implemented by the loop's queue). The measurement
loop de-energizes through the first and reports through the second; above it, it names only the
`RunEvent` vocabulary. Programs see only `RunControl`.
[`ArchitectureTest`](#the-structure-lock--architecturetest) is the rule as a test.

## One run, end to end

1. `TestRunnerService#start` → `TestRunnerThread#startThread`: boot-de-energize gate (I7), clear
   the latch, the run scope (logger, gates snapshot, run files, gap recorder, `stop-fallback` and
   persist executors), a `TestStateMachine`, and a `RunLoop` on the `TestRunnerThread` thread.
2. STARTING entry, on the loop: the runner's `Startup` callback gates the parameter (I21), takes
   the program from `TestProgramRegistry` (one factory per `TestType`, or no context), attaches it
   with a `RunDevices`, prepares the bench, runs the startup checks. `RunLoop#begin` then runs the
   one start sequence: `Limits` → `ForceLimits`, the measurement loop, `onStart`, the first sample
   at the device and in the loop (I10), the drive connect, `energize(program.startup()::applyTo)`.
3. RUNNING: `MeasurementLoop` blocks in `poll(timeout)` — the timeout is the no-data watchdog —
   offers each drain to `TraceWriter`, runs the detectors and emits `LimitCrossed` once per edge.
   The loop hands crossings and timed turns to the program, which answers a `Verdict` and acts
   only through `RunControl`.
4. Incidents: a detector de-energizes through `EmergencyStop`, flushes, opens the gap, then emits
   `SensorLost`; `RunLoop#accept` commits `SENSOR_LOST` on that thread. Hold entry, `SAFE_HOLD`,
   the reconnector worker, the hold deadline and the re-broadcast `Tick` are the loop's; a Resume
   commits `RESUMING` under `ResumeGates` and the loop runs the resume sequence (I11).
5. Any ending — verdict, `OperatorStop`, `Abort`, a trip, `PersistenceFailed` — runs `safeStop`,
   both teardowns, `onFinished(cause)`, `FINISHED` unless already terminal; the terminal entry
   re-asserts the stop, disconnects the drive, closes a gap still open and records the outcome.

The loop reaches devices, the stop and the incident bookkeeping through three seams it declares —
`RunLoop.Devices` (`RunDevices`), `RunLoop.Safety` and `RunLoop.Incidents` (adapters in
`TestRunnerThread`) — so `RunLoopTest` drives it with no thread but its own.

## The structure lock — `ArchitectureTest`

`command-deck/src/test/java/ch/rupfizupfi/deck/ArchitectureTest.java`, on the ArchUnit core
library (`com.tngtech.archunit:archunit`, plain Jupiter tests; production classes only). Each
crossing is an allow-list, so a new edge fails the test and has to be argued into it:

| Rule | Allowed |
|---|---|
| `program` → `run` | `RunControl` (with `Envelope`), `Verdict`, `Limit`, `Direction`, `FinishCause`, `RunEvent` (a `TimedTurn`), `MotorStop` (added 2026-10-13 for the destructive suction) |
| `program` → `measure` / `device` / `testrunner` | `RunEventSink` / `device.api.Drive` / `RunParameters`, plus `TestRunnerFactory` and `SuckJob` for `DestructiveProgramFactory` alone |
| `program` | no `volatile` field, no `synchronized` method or block (the source is scanned for blocks) |
| `measure` → `run` / `testrunner` | `RunEvent`, `Limit`, `Direction` / `SafeStopResult`, `RecoveryGates`, `GapRecorder`; never `program` |
| `run` → `program` | `TestProgram`, `DriveSetup`, `Limits`, `Ramp`, `ResumePolicy` |
| `run` → `measure` | not `MeasurementLoop` (bar its `GateResult`), `LimitEdgeDetector`, `TraceWriter` |
| `run` → `testrunner` | `TestState`, `TestStateMachine`, `SafeStopResult`, `RecoveryGates` |
| module-wide | `run`/`program`/`measure`/`device` never name `deck.api`; `device` never names `testrunner`; no class names `Cfw11`; no reflective construction in `testrunner`; no throwable in `run`/`program`/`measure` and no `FinishTestException`, `SignalListener`, `AbstractTest`, `TestContext`; every `RunEvent` nested in `RunEvent` |

## Constraints the structure keeps

- **The de-energize runs on the thread that detected the fault.** The recovery design
  de-energizes first and notifies after, and its latency guarantee depends on that
  ([`safety-and-recovery.md`](safety-and-recovery.md)): a detector calls `EmergencyStop#stop`
  before it enqueues, and the loop's terminal entry action re-asserts the stop, which
  `MotorSafetyController#safeStop` makes idempotent. Policy lives in the loop; the stop never
  queues behind it.
- **The three-tier ladder, its command order, and the escalation gate** (responsive-or-interrupted
  never escalates) stay whole in `MotorSafetyController`.
- **The measurement loop's detectors**, including the rise-only plausibility rule and the
  NaN-safe envelope.
- **The synchronous locked-lambda drive model.** The driver's `CommandChain` stays unused; a
  safety stop must know whether the motor stopped.
- **The `Drive` contract's method set.** `DriveSetup` wraps `setControlParameters` on the deck
  side; a typed control word in the contract is a 2.0 for a second drive model (A-09).

## A full inbound queue is a dead loop

`MeasurementLoop`'s inbound queue holds `QUEUE_CAPACITY_BATCHES`, about twenty seconds of driver
batches. A full queue means every detector decides on a force that old, so the first refused
batch is handled as a dead loop (I3): the driver's reader thread de-energizes through `EmergencyStop`, then emits `WatchdogFailed`, and the run ends
`FAULT`; later batches are only counted. `MeasurementLoopTest#anInboundOverflowDeEnergizesOnTheReaderThreadThenEndsTheRunAsAWatchdogFailure`
stalls the consumer on a latch, fills the queue, and asserts the stop on the reader thread before
the event, once.

## Safety invariant register

The safety rules the run path must keep. Code comments and tests cite them by id (`I3`, `I13`,
…); a change to the code that holds one names the invariant and keeps or ports the test that
proves it.

| Id | Invariant | Held by |
|---|---|---|
| I1 | Latch checked after the stop epoch is read, and the energize sent as one linked group fenced on that epoch; reversals and phase setpoints alike | `MotorSafetyController#energize`, `#withDriveUnlessLatched`, `#runFenced` |
| I2 | `safeStop` never throws; an escalation past tier 1 is replayed, not repeated, except a tier 2 that timed out on the lifecycle lock, which did nothing and is retried by the next `safeStop` (BL-212) | `MotorSafetyController#performStop`, `catch (Throwable)`, `#lastResultRetryable`; `MotorSafetyControllerTest#aTierTwoThatTimesOutOnTheLifecycleLockTouchesNoHandleAndIsRetriedByTheNextStop` |
| I3 | De-energize before bookkeeping: a detector stops the motor before it flushes, records or notifies | `MeasurementLoop#sensorLost` step order, `#forceCeilingCrossed`, `#run`'s `catch (Throwable)`, `#inboundOverflowed` |
| I4 | The stop is `Drive#stop()` (general enable, start and reference together), the one-write de-energize re-sent until read back; never ramp or reverse | `MotorSafetyController#commandStop`, `#verifyStopped` |
| I5 | No lock around a drive call; on a gated plugin every stop-path drive call is bounded by the driver (STOP deadline, close bound), and a stop that misses its deadline is tier 3, never tier 2. Tier 2's wait for the `Device` lifecycle lock is bounded at `RUN_EXCLUSIVE_WAIT_MS` (BL-212). Not bounded: on an ungated plugin tier 1's stop and the boot de-energize, which also run unserialized beside the poll | `MotorSafetyController#stopWithExistingHandle` (`DriveDeadlineException`), `DriveLane.STOP_DEADLINE_MILLIS`, `FrequencyInverterDevice#closeDriveHandle`, `#runExclusive` |
| I6 | Only a plugin that gates its own calls is energized; the driver that armed the link-loss stop feeds it, the deck sends no keep-alive | `MotorSafetyController#refuseUngated`, `SimulatedLinkKeepAlive` |
| I7 | A Start waits for the boot de-energize, bounded, and is refused past the bound; LOCAL skips the de-energize (OQ-87) | `BootDeEnergizeGate`, `TestRunnerThread#awaitBootDeEnergize`, `StartupRecoveryRunner` |
| I8 | Context close stops an open drive before any other bean, bounded | `ShutdownSafeStop`; `RunLoop#run` waits in `poll` holding no lock |
| I9 | A ceiling crossing ends the run `FAULT` with a verified stop, checked even while gating | `MeasurementLoop#forceCeilingCrossed`, `ForceLimits#getForceCeiling`, `RunLoop#accept` |
| I10 | Energize only after a fresh device sample **and** the loop's first sample; resume re-checks `awaitFreshMeasurement && isDataFlowing` | `RunDevices#awaitFirstSample` (`MeasurementLoop#awaitFirstSample`), `RunLoop#begin`; `RunLoop#resume` → `RunDevices#freshDataFlowing` |
| I11 | The latch is cleared for a resume only while still `RESUMING`, under the state lock; a resume superseded after its energize is undone by a `safeStop` | `TestStateMachine#runWhileIn`, `RunLoop#resume` |
| I12 | An operator Stop is never swallowed and latches before any hardware; a wedged runner is joined, interrupted, then bypassed with a direct `safeStop` | `RunEvent.OperatorStop`, `TestRunnerThread#stopThread` |
| I13 | The envelope folds only plausible samples; a non-finite limit is refused; NaN never decides a crossing | `MeasurementLoop#inspectSample`, `Limits` (every constructor), `LimitEdgeDetector#observe`'s `isFinite` guard |
| I14 | The four detectors and their thresholds, including the rise-only plausibility vote and the sparse-drain vote that also fails an open recovery gate | `MeasurementLoop#inspectSample`, `#isImplausible`, `#pollTimeoutNanos` / `#noDataTimedOut`, `#voteOnSampleInterval`, `#scoreGateSample` |
| I15 | Per-run state is reset at start, refused while a stop executes | `MotorSafetyController#clearStopLatch` |
| I16 | A crossing queued before a hold never reaches a re-initialising drive | `RunLoop#disposition` (the state rule), `#discardMotionEvents` |
| I17 | Hold entry stops the timed phase before `SAFE_HOLD`; no stale timed reversal fires into a resumed run | `TimeCyclicProgram#onHoldEntry` → `TimedCyclePhase#close`, `RunLoop#disposition`, `CalibrationPhase` dropping a `TimedTurn` |
| I18 | Tier 2 closes, opens, commands, verifies, closes and invalidates in one hold of the device's lifecycle lock and monitor (OQ-50), or does none of it when the lock is not free within its bound | `MotorSafetyController#stopWithFreshHandle`, `FrequencyInverterDevice#runExclusive` |
| I19 | The link-loss stop is armed before the energize action; a drive that cannot arm it is not energized | `MotorSafetyController#armLinkLossStop` inside `#energize` |
| I20 | The force CSV is byte-identical, one file across a hold, flushed before the gap record | `TraceWriter` (row format, `flush(bound)`), `MeasurementLoop#sensorLost` |
| I21 | A run with an unrunnable parameter faults before any device is touched | `RunParameters#requireRunnable` in `TestRunnerThread#start` (the STARTING entry) |

## Still owed

The redesign's bench checks — none recorded as done — are OQ-88; the time-cyclic
direction-and-travel check that predates it is OQ-63.
