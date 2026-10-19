# Test execution engine

## Purpose

A test run is the most safety-relevant operation in the system: an electric
motor pulls or releases a physical specimen until something either reaches a
force threshold or breaks. This page covers the **control plane** — how a run
starts, is driven, and is shut down.

The test types themselves (the `TestProgram` contract, the three programs and
their registry, the verdicts that end a run, and the measurement thread the loop
starts) are in [`test-types.md`](test-types.md).

## Contents

- [Diagram — lifecycle](#diagram--lifecycle)
- [Entry point — `TestRunnerService`](#entry-point--testrunnerservice)
- [`TestRunnerFactory`](#testrunnerfactory)
- [`TestRunnerThread.run()`](#testrunnerthreadrun)
- [`RunLoop` — events and entry actions](#runloop--events-and-entry-actions)
- [`Limits` and `ForceLimits` — limits and ceiling](#limits-and-forcelimits--limits-and-ceiling)
- [Startup checks](#startup-checks)
- [Unrunnable parameter types](#unrunnable-parameter-types)
- [Where to look in the code](#where-to-look-in-the-code)
- [Known limits](#known-limits)

## Diagram — lifecycle

```mermaid
sequenceDiagram
    autonumber
    participant FE as React (run.tsx)
    participant Hilla as TestRunnerService
    participant TRT as TestRunnerThread
    participant TRF as TestRunnerFactory
    participant Test as TestProgram<br/>(Destructive/Cyclic/TimeCyclic)
    participant Loop as RunLoop<br/>(on the runner thread)
    participant LCT as MeasurementLoop
    participant CFW as Drive<br/>(via MotorSafetyController)
    participant LC as LoadCellDevice

    FE->>Hilla: start(testId)
    Hilla->>TRT: startThread(testResult)
    TRT->>TRF: createLogger(testResult)
    TRT->>Loop: new RunLoop, run() on its own Thread
    Loop->>Loop: STARTING entry: sleep(50)<br/>wait for client subscription
    Loop->>TRF: createProgram(testResult)
    TRF->>Test: TestProgramRegistry, by TestType
    Loop->>Loop: startup checks
    Loop->>Test: limits()
    Loop->>TRF: createMeasurementLoop(limits, loadCell, loop)
    TRF-->>LCT: new MeasurementLoop, start()
    LCT->>LC: connect() + registerObserver(this)
    Loop->>LC: fresh sample, then the loop's first sample
    Loop->>CFW: energize(program.startup()):<br/>speed, ramp, one control word
    Loop->>Loop: STARTING -> RUNNING

    loop until an event ends the run
        LC-->>LCT: update(measurements)
        LCT->>LCT: offer batch to TraceWriter (own thread), track min/max
        LCT->>Loop: LimitCrossed(UPPER or LOWER), once per edge
        Loop->>Test: onLimit(limit, control) -> Verdict
        Test->>CFW: control.turn (cyclic types)<br/>log to /topic/logs
    end

    Note over Loop,Test: Verdict.finish, Stop, Abort or a trip
    Loop->>CFW: safeStop, re-asserted by the terminal entry
    Loop->>Test: teardown(control), onFinished(cause, stop, control)<br/>then the drive disconnect
```

## Entry point — `TestRunnerService`

`TestRunnerService`
(`command-deck/src/main/java/ch/rupfizupfi/deck/api/services/TestRunnerService.java:13`)
is `@BrowserCallable @PermitAll`. It owns *one* `TestRunnerThread` (created
once via `TestRunnerFactory.createTestRunnerThread()` in the constructor).
`start(Long testId)` looks up the `TestResult` row and hands it to the
thread; `status()` returns whether the thread is running plus the active
`TestResult` (or `null`); `stop()` asks the thread to wind down; `resume()`
re-arms the watchdog and re-energizes the drive after a sensor loss, and
`abort()` ends a paused run — both return a refusal reason when refused.

The service is a singleton holding one thread, so **only one test runs at a
time** on this server — one motor, one specimen.

On a real bench a Start waits up to 5 s for `StartupRecoveryRunner`'s boot
de-energize and is then refused as "bench still initialising", so the
de-energize can never land mid-setup. On context close `ShutdownSafeStop`
stops the motor before any other lifecycle bean; both are in
[loadcell-recovery-design.md](safety-and-recovery.md#state-machine-and-persistence).

## `TestRunnerFactory`

`TestRunnerFactory` (`command-deck/.../testrunner/TestRunnerFactory.java:15`)
is a `@Service` that hands a run its per-run collaborators.
`createProgram(TestResult)` asks the `TestProgramRegistry` for the type's
program; there is no reflection left (OQ-64 step 7 deleted
`createTestRunner`). The registry refuses a context in which a `TestType` has
no factory, see [`test-types.md`](test-types.md#testprogramregistry).

It also exposes `createMeasurementLoop(ForceLimits, LoadCellDevice, ...)`,
`createLogger(TestResult)`, `rigLimits()`, and `getStartupChecks()` — the three
[startup checks](#startup-checks)
(`command-deck/src/main/java/ch/rupfizupfi/deck/testrunner/TestRunnerFactory.java:75`).

## `TestRunnerThread.run()`

`TestRunnerThread` (`command-deck/.../testrunner/TestRunnerThread.java:10`)
is **not** a `Thread`; it owns one.

1. `startThread(TestResult)` creates a `TestLogger`, calls `begin()` (opens
   the per-test log file under the result-data location), and starts the
   `Thread` named `TestRunnerThread`. Every failure on that path releases the
   run scope and throws, so a start that began no run reaches the operator as
   an error rather than as a silent success.
2. The thread sleeps 50 ms so the React client can subscribe to `/topic/logs`
   before the first lines. This is a race, not a guarantee (OQ-23, see
   [`dev/frontend/state-and-realtime.md`](../frontend/state-and-realtime.md)).
3. The thread runs the run's `RunLoop` ([below](#runloop--events-and-entry-actions)).
   Its STARTING entry action gates the parameter — a parameter with no type
   faults first, see [below](#unrunnable-parameter-types) — takes the program
   from the registry, prepares the bench and runs the startup checks. The loop
   then runs the one start sequence of every type: the program's limits, the
   `MeasurementLoop`, the program's start lines, the first sample at the device
   **and** in the loop (I10), the drive connect, and the program's
   `DriveSetup` as the one energize. The stop latch is cleared in `startThread`.
4. The loop's terminal entry action runs exactly once on every exit: `safeStop`,
   the devices' and the program's teardown, the drive disconnect. If *those*
   throw — USB cable yanked, say — it retries the disconnect and runs `safeStop` again, whose tier 2 drops the
   device-service handle and opens a fresh one through `DriveProvider` to force
   the motor off. **This is the most safety-critical block in the codebase.**
   Then a terminal state is settled, the outcome recorded and the `TestLogger`
   ended on every exit. `TestLogger.end()` claims the writer in a single read,
   so it is idempotent against a concurrent `end()` from `stopThread()`; `log()`
   absorbs a rejected STOMP broadcast as well as a file-write failure.

`stopThread()` commits `STOPPING` (`ABORTED` in STARTING or an incident),
latches the stop without touching the drive, then enqueues `OperatorStop`; past
1 s it interrupts the thread, past 2 s more it stops the motor itself.
`abortTest()` commits `ABORTED`, its `stop-fallback` thread stops the motor
and enqueues `Abort`; `resumeTest()` commits `RESUMING`, enqueues `ResumeAccepted`.

## `RunLoop` — events and entry actions

`RunLoop` (`command-deck/src/main/java/ch/rupfizupfi/deck/testrunner/run/RunLoop.java`)
consumes a sealed `RunEvent` — `LimitCrossed`, `TimedTurn`, `OperatorStop`,
`Abort`, `CeilingTripped`, `WatchdogFailed`, `PersistenceFailed`, `SensorLost`,
`Recovered`, `ResumeAccepted`, `Tick` — and is the only thread that runs program code, entry
actions and the teardown. It holds no lock while it waits.

* **State is committed where the cause is seen**; the event tells the loop to
  act. Operator commands commit on their request thread, a trip or a sensor
  loss in `RunLoop#accept` on the measurement thread after it de-energized. The
  loop's own transitions are refused once the run is terminal, so an Abort
  committed first wins over a finish the loop computes afterwards.
* **The state rule is the dispatch gate**: `LimitCrossed` and `TimedTurn` act
  only in `RUNNING`, incident events only in the state their emitter committed,
  ending events in any state; the rest is dropped with a log line.
* **A program answers with a `Verdict`**, and acts only through the loop's
  `RunControl`. Any ending runs `safeStop`, the teardown, `onFinished(cause, stop)`
  and the closing log line, then `FINISHED`
  unless already terminal; a throw goes straight to the terminal entry, `FAULT`.
* **The incident path is entry actions** (sequence in the
  [recovery design](safety-and-recovery.md)):
  hold entry and `SAFE_HOLD` on `SensorLost`, the resume on `ResumeAccepted`,
  re-broadcast and hold expiry on `Tick`. The loop also ends on a terminal
  state it committed itself; the terminal entry closes a gap still open.

## `Limits` and `ForceLimits` — limits and ceiling

A program declares its `Limits` (`command-deck/.../testrunner/program/Limits.java`):
upper, lower and ceiling in newton. The run loop copies them into the
`ForceLimits` holder (`command-deck/.../testrunner/measure/ForceLimits.java`)
the measurement loop reads per sample; what a run reacts to travels on the
`RunLoop`. The repeat filter the old signal queue applied lives in
`LimitEdgeDetector`, shared with the time-cyclic test's timed turns — see
[`test-types.md`](test-types.md#measurementloop--the-force-watchdog).

The cyclic programs move the limits through `RunControl#adaptLimits` to
compensate for overshoot: measured min/max diverge from target, so the limit is
nudged to turn the motor around earlier next cycle. `Limits` refuses a
**non-finite** limit on every construction, an adaptation included, and
`MeasurementLoop` skips a non-finite sample rather than comparing it: a NaN makes
every comparison false, so no crossing would fire with the motor still driving.

## Startup checks

`AbstractCheck`
(`command-deck/.../testrunner/startup/check/AbstractCheck.java`) defines a
single `execute() throws CheckFailedException`. Three implementations:

* `FileSystemCheck` — the result-data directory exists and is writable.
* `LoadCellCheck` — the load cell delivers a *fresh* measurement within 2 s.
  Opening the device is not evidence: the driver opens the port on its own
  thread, so a device that is not plugged in still connects and reports itself
  connected.
* `FrequencyInverterCheck` — one `getControlParameters` read through the shared
  drive handle proves the inverter answers. Nothing is written, and the connect
  is balanced so the run's own connect still opens a fresh reference count.

`TestRunnerThread` runs them in its STARTING entry and aggregates failures into
one combined `CheckFailedException`.

To add a check: subclass `AbstractCheck` and return it from
`TestRunnerFactory.getStartupChecks()` — no `@Component` auto-discovery.

## Unrunnable parameter types

`TestProgramRegistry` has exactly one program factory per `TestType` constant,
or the context does not start: a fourth constant without a program fails the
context tests. Every type the model can hold is runnable, so two cases are
left, and both are refused before any device is touched (I21):

* **No type.** `RunParameters#requireRunnable` (through
  `TestParameterRules#requireValid`) refuses it ahead of the registry. The run
  log gets `unknown test parameter type: null` and the run settles `FAULT`
  with the same reason, which `TestStateBroadcaster` carries to the operator.
* **A stored value outside the three.** `TestTypeConverter` refuses it when
  the row loads, naming the value, so it never reaches a runner (see
  [`persistence-model.md`](persistence-model.md#testparametertype-is-an-enum)).

## Where to look in the code

| Concern | File |
|---|---|
| Hilla entry point | `command-deck/src/main/java/ch/rupfizupfi/deck/api/services/TestRunnerService.java:13` |
| Factory | `command-deck/src/main/java/ch/rupfizupfi/deck/testrunner/TestRunnerFactory.java:15` |
| Thread driver | `command-deck/src/main/java/ch/rupfizupfi/deck/testrunner/TestRunnerThread.java:10` |
| Run loop and its events | `command-deck/src/main/java/ch/rupfizupfi/deck/testrunner/run/` |
| Programs and their registry | `command-deck/src/main/java/ch/rupfizupfi/deck/testrunner/program/` |
| Limits and ceiling | `command-deck/src/main/java/ch/rupfizupfi/deck/testrunner/program/Limits.java` |
| Logger (file + STOMP `/topic/logs`) | `command-deck/src/main/java/ch/rupfizupfi/deck/testrunner/TestLogger.java:17` |
| Pre-test checks | `command-deck/src/main/java/ch/rupfizupfi/deck/testrunner/startup/check/` |

Test implementations and the measurement thread: [`test-types.md`](test-types.md).

## Known limits

- The safe-stop escalation opens a second drive handle on the same USB device
  (`MotorSafetyController#stopWithFreshHandle` → `DriveProvider.open()`). Tier 2 closes the old
  handle first, under the device's lifecycle lock and monitor, and opens none while that close is pending, so
  the overlap is meant to be zero; whether two modbus handles on one device are safe is not settled
  (OQ-50).
- The run loop's reversals (`turn`) and phase setpoints (`runPhaseSpeed`) go through
  `withDriveUnlessLatched`: one linked group fenced on the drive's stop epoch, so one queued before
  a stop never runs after it ([`safety-and-recovery.md`](safety-and-recovery.md)).
- The engine's package structure, its dependency rule and `ArchitectureTest` are in
  [`testrunner-architecture.md`](testrunner-architecture.md).
