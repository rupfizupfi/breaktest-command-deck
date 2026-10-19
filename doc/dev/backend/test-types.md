# Test types and termination

## Purpose

What a test type *is*: a declared `TestProgram`, the three programs, the
`TestProgramRegistry` that maps every `TestType` to one, the verdicts that end
a run, and the `MeasurementLoop` the run loop starts to turn force readings into
limit crossings.

The control plane that drives these — service, runner thread, run loop — is in
[`test-execution-engine.md`](test-execution-engine.md).

## Contents

- [`TestProgram` — the contract](#testprogram--the-contract)
- [The three programs](#the-three-programs)
- [Time-cyclic phases and the hold](#time-cyclic-phases-and-the-hold)
- [`TestProgramRegistry`](#testprogramregistry)
- [`MeasurementLoop` — the force watchdog](#measurementloop--the-force-watchdog)
- [Verdicts and the double stop](#verdicts-and-the-double-stop)
- [Where to look in the code](#where-to-look-in-the-code)
- [Parameter rules](#parameter-rules)

## `TestProgram` — the contract

Since OQ-64 step 7 a type declares what it is; the run loop owns the one start
sequence, every transition, the motor stops and the incident path. A program
(`command-deck/src/main/java/ch/rupfizupfi/deck/testrunner/program/TestProgram.java`)
answers:

* `limits()` — upper, lower and ceiling in newton, as a `Limits` that refuses a
  non-finite value on every construction (I13); a `+Infinity` ceiling means none.
* `startup()` — a `DriveSetup(rpm, Direction, Ramp or null)`. Its
  `applyTo(Drive)` is the energize written once: speed, then the ramp times (or
  `setUseSecondRamp(false)` without one, so no run inherits a second ramp a
  failed cleanup left), then **one** control word
  `setControlParameters(start, generalEnable, direction, null, ramp != null)` —
  owner decision D3, the same one-write pattern as the verified stop.
  `MotorSafetyController#energize` wraps it: latch check and stop-epoch fence,
  link-loss arming before it, all one linked group.
* `onStart(control)` — the type's run-log lines; `onLimit(limit, control)` and
  `onTimedTurn(next, control)` — a `Verdict` (`CONTINUE` or `finish(cause)`).
* `resumePolicy()` — a `ResumePolicy`: whether a hold may end in a resume
  (`supportsResume()`, constant per run because `ResumeGates` asks it on request
  threads) and how it re-energizes (`resumeSetup(control)`, loop thread).
  `ResumePolicy.ABORT_ONLY` for destructive, `TowardNearerLimit` for cyclic,
  `RestartCalibration` for time-cyclic. The gates themselves stay in
  `run/ResumeGates`.
* `onHoldEntry()`, `teardown(control)` (after every teardown stop, so
  idempotent), `onFinished(cause, stop, control)` — `stop` is the `MotorStop`
  the loop maps off the finish stop's `SafeStopResult` (`CONFIRMED`,
  `COASTING`, `UNCONFIRMED` = `needsOperatorAttention()`, `NEVER_ENERGIZED`).

A program acts on its run only through `RunControl`, which `RunLoop`
implements, all on the loop thread: `turn(Direction)` (one write, refused with
a log line while a stop is latched; the commanded direction is cached, never
read back — a dedup, not a safety check), `direction()`, `envelope()` and its
posted resets, `limits()` and `adaptLimits(upper, lower)`, `runPhaseSpeed`,
`disableSecondRamp`, `lastForce()`, `timedTurns()`, `log`.

## The three programs

| Program | Declares | Behaviour |
|---|---|---|
| `DestructiveProgram` | shut-off thresholds as limits, no ceiling; pulls at the run speed, no ramp | Either crossing answers `finish(LIMIT)` and is remembered. Abort-only. `onStart` resets the envelope maximum to 0 so it reads as the run's peak. The loop safe-stops **first**; only then `onFinished(LIMIT)` decides the [suction](#destructive-suction) |
| `CyclicProgram` | turn forces as limits, `upperShutOffThreshold` as the ceiling; releases at the run speed and ramps | `CyclicTurns` reverses **before** any limit maths or log line, from the cached direction; adapts the limits by the overshoot toward the target; the last cycle answers `finish(CYCLES_DONE)`. Resumes toward the nearer limit (unknown force: release). Its teardown switches the second ramp off |
| `TimeCyclicProgram` | as cyclic; calibration releases at 50 mm/min — clamped to the run's `RigLimits` maximum speed when that is lower, with one run-log line saying so — on a 0.3 s second ramp | Two `Phase`s over one shared `CyclicTurns`, so the limit adaptation and cycle count carry across a resume. `CalibrationPhase` reverses on every crossing and times the release and pull legs on the edges whose envelope came within 300 N of the turn force; once both legs are timed, the next turn is the run's first cyclic turn, then `runPhaseSpeed` applies the run speed and ramps, and it hands over to `TimedCyclePhase` with both times scaled by calibration speed / run speed. `TimedCyclePhase` schedules the opposite of every turn as a `TimedTurn` through `MeasurementLoop#timedTurns`, the crossings' own repeat filter. A hold entry closes the timed phase and recalibrates; the resume (`RestartCalibration`) calibrates again on the calibration setup. See [below](#time-cyclic-phases-and-the-hold) |

## Destructive suction

`SuckJob` (relay 1 on for `TESTRUNNER_SUCK_DURATION` seconds, if
`TESTRUNNER_SUCK` is on) starts only for a break (owner, 2026-10-13): the run
ended `LIMIT` on the **LOWER** crossing, its peak (the envelope maximum)
reached the lower shut-off plus the [suction load margin](persistence-model.md#rig-limits)
(500 N by default, from the run's `RigLimits` snapshot), and the stop came back
`CONFIRMED`, `COASTING` or `NEVER_ENERGIZED`. Every other `LIMIT` end writes one
run-log line and the run still ends `FINISHED`:

| Ended on | Log line |
|---|---|
| UPPER shut-off | `suction skipped: upper shut-off reached, specimen held` |
| LOWER, peak below lower + margin | `suction skipped: no load built (…) - the test was not started correctly` |
| LOWER, load built, stop `UNCONFIRMED` | `suction skipped: motor stop unconfirmed, use E-stop` |

`OPERATOR`, `ABORT` and `FAULT` never start it and log nothing; a lower
shut-off of 0 leaves the margin alone to decide. Blind spots: a break at the
upper shut-off ends UPPER, and a clamp slip after load looks like a break. The
simulator mounts above the lower shut-off, so `DestructiveProgramTest` alone
holds the no-load row.

A refused off command is retried, then alarmed: see
[the relay](hardware-integration.md#4-way-relay--serial-via-jserialcomm).

## Time-cyclic phases and the hold

Every phase change is on the loop thread: crossings, timed turns, the hold
entry and the resume are all loop calls, so the phases need no lock and the
pre-step-8 hold epoch, `processorLock` and `volatile` flags are gone (H-15).
No stale timed reversal reaches a resumed run, by three layers:

1. **The hold entry closes the timed phase before `SAFE_HOLD` is committed.**
   `TimedCyclePhase#close()` is `shutdownNow()` plus a bounded wait for a task
   already posting, so once it returns nothing the phase scheduled can still
   reach the run (I17).
2. **The state rule.** The loop acts on a `TimedTurn` only while `RUNNING`; one
   queued before the hold is dropped, and the resume's `discardMotionEvents()`
   clears any still queued.
3. **Calibration schedules nothing**, so a timed turn reaching it came from a
   closed phase and `CalibrationPhase` drops it without moving the motor.

`TimeCyclicProgramTest`, `CalibrationPhaseTest`, `TimedCyclePhaseTest` and
`RunLoopTest#aTimeCyclicHoldDropsEveryTimedTurnAndTheResumeRecalibratesOnTheCalibrationSetup`
hold these; the step-1 scenario `time-cyclic-loss-recalibrates` pins the run
log and drive writes end to end.

## `TestProgramRegistry`

A `@Component` holding an `EnumMap<TestType, TestProgramFactory>`, with one
`@Component` factory per type. Its constructor throws unless every `TestType`
has exactly one factory, naming the type, so a fourth type without a program
fails context startup — `ApplicationContextTest` is that gate — instead of its
first run. It replaced `TestRunnerFactory#createTestRunner`'s reflective
constructor lookup and the switch in `TestRunnerThread`. A parameter with no
type is storable but unrunnable — see
[`test-execution-engine.md`](test-execution-engine.md#unrunnable-parameter-types).

## `MeasurementLoop` — the force watchdog

`MeasurementLoop` (`command-deck/.../testrunner/measure/MeasurementLoop.java`,
`LoadCellThread` until OQ-64 step 10) is both a `MeasurementObserver` — so
`LoadCellDevice` pushes data into it — and the holder of a writer thread. The
run loop's start sequence creates it on the program's limits, held in a
`ForceLimits` the loop moves when a cyclic program adapts them. It names
nothing above `testrunner.measure` but the `RunEvent` vocabulary: it stops the
motor through `EmergencyStop` and reports through `RunEventSink`
(`ArchitectureTest`). Its `run()`:

1. Opens the run's `TraceWriter` (`testrunner/measure/`) on a per-result CSV
   path (`CSVStoreService.generateFilePathForTestResult(testResultId)`): a
   `BufferedWriter` owned by a `trace-writer-*` thread behind a bounded queue
   of batches (`QUEUE_CAPACITY_BATCHES`), so no disk write sits on the
   detection path (A-07, OQ-64 step 9).
2. Connects the load cell and registers itself as observer.
3. Blocks in `poll(timeout)` on a bounded queue that `update()` fills from the
   reader thread, so a batch wakes it at once; the timeout is what is left of the
   250 ms no-data watchdog since the last batch was taken, so a poll that runs out
   on an armed loop is the trip. It takes every queued batch as one drain, offers
   it to the trace without waiting (each row stays `timestamp,force` and a
   line separator), updates min/max (the loop is the
   envelope's only writer: a program's envelope reset is posted and applied
   before the next fold, so it cannot be lost), and hands
   each sample to `LimitEdgeDetector`, which emits `LimitCrossed(UPPER|LOWER)`
   on the run loop once per edge: the drain's first out-of-range sample decides,
   and nothing is sent when the drain also trips the ceiling or a detector, or
   while a recovery gate is open. The repeat filter is shared with timed turns:
   a timed `PULL` re-arms the upper edge, and a turn repeating the last one is
   dropped.
4. A ceiling crossing, a sensor loss or the loop's own death de-energizes
   first, then emits `CeilingTripped` / `SensorLost` / `WatchdogFailed`; a
   loss also hands the queued tail to the trace, flushes it (bounded by
   `FLUSH_BOUND_MS`, so a stalled disk delays the gap record by that and no
   more) and opens the gap record before its event; a ceiling trip flushes too.
5. On exit, unregisters, disconnects the load cell, then closes the trace,
   which writes its queued tail first.

The inbound queue holds about twenty seconds of batches. A full one means every
detector decides on a force that old, so its first refused batch is a **safety
event**: the reader thread de-energizes, then emits `WatchdogFailed`, and the run
ends `FAULT` as for a dead loop (step 10; before it the batch was only dropped).

A batch the trace cannot queue, or a write that fails, raises one
`PersistenceFailed`: not a safety event, so the writer touches no hardware and
the run loop ends the run `FAULT` through its normal stop. The CSV flushes on
close, on a loss and on a ceiling trip, so
**killing the JVM mid-test loses the unflushed tail of the CSV.** `TestLogger`
flushes on every `log()` call.

An unplugged USB cable trips the no-data watchdog into a verified safe stop,
then reconnect-and-resume — the writer stays open across the hold, so the gap
lands in this same file as an epoch-millis discontinuity and is explained by a
`<millis>_gaps.json` sidecar. See
[`safety-and-recovery.md`](safety-and-recovery.md).

## Verdicts and the double stop

A program ends its run by answering `Verdict.finish(cause)` — `LIMIT`,
`CYCLES_DONE` — and the loop ends it on `OperatorStop` (`OPERATOR`), `Abort`
(`ABORT`) and a trip (`FAULT`); a run an Abort or a trip already ended reaches
`onFinished` as that, whatever the verdict said. Every such ending runs, on the
loop thread:

1. `MotorSafetyController#safeStop`, then the devices' teardown (measurement
   loop, reconnector) and the program's `teardown(control)`;
2. `onFinished(cause, stop, control)`, then the `<Type> finishing test` log line
   (`name()` keeps the old class names, `DestructiveTest` and so on, so the run
   log reads as before);
3. `FINISHED`, unless the run is already terminal;
4. the terminal entry action: `safeStop` again, both teardowns again, then the
   drive disconnect.

The second stop is the terminal entry re-asserting the first, as the runner's
`finally` always did, and it is pinned by the step-1 goldens. It is also the
only stop on a path that throws — an exception from the start sequence or from
program code — so a program's `teardown` must be idempotent.

## Where to look in the code

| Concern | File |
|---|---|
| Programs, setups, limits, registry | `command-deck/src/main/java/ch/rupfizupfi/deck/testrunner/program/` |
| What a program may do | `command-deck/src/main/java/ch/rupfizupfi/deck/testrunner/run/RunControl.java` |
| Verdicts, causes, events, the loop | `command-deck/src/main/java/ch/rupfizupfi/deck/testrunner/run/` |
| A run's devices (measurement loop, reconnector, drive connection) | `command-deck/src/main/java/ch/rupfizupfi/deck/testrunner/RunDevices.java` |
| Load-cell observer (feeds the CSV + emits crossings), reconnector, edge detector | `command-deck/src/main/java/ch/rupfizupfi/deck/testrunner/measure/MeasurementLoop.java` |
| Force CSV writer thread | `command-deck/src/main/java/ch/rupfizupfi/deck/testrunner/measure/TraceWriter.java` |
| Post-destructive cleanup actuator | `command-deck/src/main/java/ch/rupfizupfi/deck/testrunner/SuckJob.java:6` |
| Time-cyclic phases, resume policies | `command-deck/src/main/java/ch/rupfizupfi/deck/testrunner/program/` (`CalibrationPhase`, `TimedCyclePhase`, `ResumePolicy`) |

## Parameter rules

Every program converts speed through `RunParameters.toMotorRpm` (mm/min ÷ 0.375 mm/rev),
and `RunParameters.requireRunnable` gates a run before any device is touched. It delegates to
the cms rule set `cms/src/main/java/ch/rupfizupfi/deck/rig/TestParameterRules.java`, checked
against the run's `RigLimits` snapshot (`TestRunnerFactory#rigLimits`, read once at run start
from the [rig-limit settings](persistence-model.md#rig-limits)), each field only for the types
that read it:

| Field | Types | Rule |
|---|---|---|
| `type` | all | known (a null is storable, refused at run start) |
| `speed` | all | 0 < speed ≤ the maximum crosshead speed (the ceiling converts to ≤ 2667 rpm, inside the CFW11 driver's P0683 bound of 4 × synchronous speed for any 2- to 8-pole motor at 50/60 Hz) |
| `upperShutOffThreshold` | all | present, finite, within (0, rated capacity] kN — the hard trip for every type |
| `lowerShutOffThreshold` | destructive | present, finite, ≥ 0 (never negative), below the upper one |
| `upperTurnForce`, `lowerTurnForce` | cyclic, time-cyclic | present, finite, 0 ≤ lower < upper < `upperShutOffThreshold` |
| `cycleCount` | cyclic, time-cyclic | present and ≥ 1 |
| `startRampSeconds`, `stopRampSeconds` | cyclic, time-cyclic | null or within [0, 999.0] s (`TestParameterRules.MAX_RAMP_SECONDS`, the CFW11's 9990-tenth ramp ceiling); null or 0 leaves the run on the first ramp |

The ramp bound is the drive's: `Drive#setSecondSpeedRampTime` refuses a time outside
0..9990 tenths on the CFW11 driver and on the simulator alike, so a run the gate passed never
has its energize refused mid-run. A lower force is never negative (owner, 2026-10-13); whether a
lone ramp should be refused is the owner's (OQ-89). `TestParameterService#save` applies the same rules, so a
parameter outside them is refused at the form; the run-start check stays because the limits can
change after the save. `MeasurementLoop`'s plausibility bounds scale with the same snapshot. The
cyclic types also enforce `upperShutOffThreshold` as an absolute ceiling in `MeasurementLoop`,
which ends the run as a FAULT. **Bench check owed:** before the OQ-63 fix the time-cyclic
setpoints were 1000× low and its analyse phase commanded 0 rpm; it now moves at about 133 rpm,
so verify direction and travel on the bench before a real specimen. **Bench check owed (D3):**
the one-write energize on each type, over a lossy link as H-06 checked the stop (OQ-88).
