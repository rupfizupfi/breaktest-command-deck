> Branch: `dev-split` — split out of `test-execution-engine.md` 2026-08-17.

# Test types and termination

## Purpose

What a test *implementation* is: the `AbstractTest` contract, the three
concrete strategies, the `FinishTestException` idiom that ends a run cleanly,
and the `LoadCellThread` each test starts to turn force readings into limit
signals.

The control plane that instantiates and drives these — service, factory,
thread, signal bus — is in
[`test-execution-engine.md`](test-execution-engine.md).

## Contents

- [`AbstractTest` — the contract](#abstracttest--the-contract)
- [The three subclasses](#the-three-subclasses)
- [`LoadCellThread` — the polling heart](#loadcellthread--the-polling-heart)
- [`FinishTestException` and the double cleanup](#finishtestexception-and-the-double-cleanup)
- [Where to look in the code](#where-to-look-in-the-code)

## `AbstractTest` — the contract

`AbstractTest` (`command-deck/.../testrunner/AbstractTest.java:12`)
implements `SignalListener` and exposes:

* `setup()` — abstract. Subclasses connect the CFW11, pre-arm the motor, and
  start the `LoadCellThread`.
* `handleSignal(int)` — abstract. This is the strategy: what to do when a
  force limit is breached.
* `cleanup()` — disable motor, stop the load-cell thread. **May run twice**
  — see [below](#finishtestexception-and-the-double-cleanup). Subclasses
  override but must always call `super.cleanup()`.
* `destroy()` — disconnect the CFW11 device and null out fields.
* `cfw11Pull()` / `cfw11Release()` — direction helpers.

## The three subclasses

| Class | Termination | Notes |
|---|---|---|
| `DestructiveTest` | `handleSignal → finish()` on **any** signal — the first threshold hit ends the test | If the run lasted > 2 s, optionally fires `SuckJob` post-test (relay 1 on for `TESTRUNNER_SUCK_DURATION` seconds). `DestructiveTest.java:32` |
| `CyclicTest` | Reverses the motor each time a limit is hit; decrements `cycleCount`; signal `0` = finish | Adjusts `upperLimit`/`lowerLimit` by the overshoot delta to converge on target. `CyclicTest.java:51` |
| `TimeCyclicTest` (extends `CyclicTest`) | As cyclic, but begins with an analyse-run at low speed to *measure* release/pull times, then switches to ramp-time control | Uses a `TimeProcessor` thread to drive direction changes by time rather than force once analysed |

These are the only three values `TestRunnerThread` dispatches on
(`"destructive"`, `"cyclic"`, `"timeCyclic"`). Any other `TestParameter.type`
is storable but unrunnable — see
[`test-execution-engine.md`](test-execution-engine.md#unrunnable-parameter-types-fail-silently).

## `LoadCellThread` — the polling heart

`LoadCellThread` (`command-deck/.../testrunner/LoadCellThread.java:15`) is
both a `MeasurementObserver` — so `LoadCellDevice` pushes data into it — and
the holder of a writer thread. Its `run()`:

1. Opens a `BufferedWriter` on a per-result CSV path
   (`CSVStoreService.generateFilePathForTestResult(testResultId)`).
2. Connects the load cell and registers itself as observer.
3. Loops on `measurementBuffer`: drain under a lock, write each measurement
   as `timestamp,force\n`, update min/max, and push a signal if the latest
   measurement breaches the upper or lower limit.
4. On exit, disconnects the load cell.

Because writes are line-buffered and flush on close, **killing the JVM
mid-test loses up to ~1 s of CSV.** `TestLogger` log lines are safe — it
flushes on every `log()` call (`TestLogger.java:39`).

An unplugged USB cable makes `getNextValues()` return empty and the run
continues recording nothing (OQ-45); the decided fix is reconnect-and-resume,
designed in
[`../06-feature-work/testrunner-safety/loadcell-recovery-design.md`](../06-feature-work/testrunner-safety/loadcell-recovery-design.md).

## `FinishTestException` and the double cleanup

`FinishTestException` (`command-deck/.../testrunner/FinishTestException.java`)
extends `Exception` and carries no fields. It is the *only* clean way for an
`AbstractTest` to tell `TestRunnerThread.run()` "stop the signal loop, we're
done":

1. `AbstractTest.finish()` (`AbstractTest.java:40`) calls `cleanup()`, logs,
   then `throw new FinishTestException();`.
2. `TestContext.processSignals()` rethrows it to `TestRunnerThread.run()`.
3. `catch (FinishTestException ignored) { }` at `TestRunnerThread.run():43`
   swallows it — no error, no logging. This is a normal exit.
4. The `finally` block runs `cleanup()` (now possibly a second time) and
   `destroy()`.

**The double cleanup is intentional.** `finish()` calls `cleanup()` itself
because subclasses like `DestructiveTest.finish` need to run the suck-job
*after* the motor is disabled but *before* the throw leaves the method. The
redundant call from `finally` then guarantees cleanup even on paths that
skipped `finish()` — an exception thrown from `setup()`, for instance. Any
`cleanup()` override must therefore be idempotent.

## Where to look in the code

| Concern | File |
|---|---|
| Abstract test | `command-deck/src/main/java/ch/rupfizupfi/deck/testrunner/AbstractTest.java:12` |
| Subclasses | `DestructiveTest.java`, `CyclicTest.java`, `TimeCyclicTest.java` (same package) |
| Cooperative-termination signal | `command-deck/src/main/java/ch/rupfizupfi/deck/testrunner/FinishTestException.java` |
| Load-cell observer (writes CSV + signals) | `command-deck/src/main/java/ch/rupfizupfi/deck/testrunner/LoadCellThread.java:15` |
| Post-destructive cleanup actuator | `command-deck/src/main/java/ch/rupfizupfi/deck/testrunner/SuckJob.java:6` |
| Cyclic-helper classes | `command-deck/src/main/java/ch/rupfizupfi/deck/testrunner/cyclic/` (`AnalyseData`, `CyclicTestContext`, `TimeProcessor`) |

## Open questions

**OQ-63**: the three runners disagree on the speed→rpm divisor — `0.375` here,
`375` in `TimeCyclicTest.java:62`, whose analyse-run setpoint therefore rounds to
0 rpm. A single conversion, and programs that declare their drive setup instead of
writing it three times, are part of
[hardware-layer-redesign](../06-feature-work/hardware-layer-redesign/README.md) (OQ-64).

Otherwise none of its own. The runner-side items (OQ-35, OQ-49, OQ-50, OQ-51) are in
[`test-execution-engine.md`](test-execution-engine.md#open-questions);
load-cell recovery is OQ-45 in
[`hardware-integration.md`](hardware-integration.md).
