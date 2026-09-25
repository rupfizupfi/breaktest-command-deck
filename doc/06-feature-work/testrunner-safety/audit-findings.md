# Audit findings — test-runner safety (2026-08-16)

Verified against source; criticals additionally adversarially verified (none refuted).
Paths relative to repo root; `deck/` = `command-deck/src/main/java/ch/rupfizupfi/deck/`.

## Critical — motor can drive blind

| # | Finding | Anchor |
|---|---|---|
| ~~C2~~ | **Closed** by the driver rebuild (`ec47aa6`): the reader now catches, closes the port and records the cause, and `running` goes false so a dead reader stops claiming to be alive. Surviving constraint: **a stopped stream cannot be restarted** — reconnection must build a new `CellValueStream` | `LoadCellDevice#getStreamFailure` |
| C5 | No shutdown hook / watchdog: JVM kill or Spring stop mid-test leaves motor to the drive's own comm-error action (`setActionInCaseOfCommunicationError(2)`) — which only covers the **CFW11 link**, not load-cell loss | `deck/testrunner/DestructiveTest.java:24` |
| C6 | Last-resort stop `retryShutdownOnException` can itself throw and escape; no alternate kill path | `deck/testrunner/TestRunnerThread.java:115` |
| ~~C7~~ | **Closed**: every sample of a drained batch is held against the limits, the earliest crossing decides and one signal is sent per drain. Surviving constraint: **the limit decision is per sample, first crossing wins** | `LoadCellThread#run` |

## High — lifecycle & control path

| # | Finding | Anchor |
|---|---|---|
| ~~H1~~ | **Closed**: `running` is set last, after the runner thread exists, and a failed `begin()` releases the run scope and throws so the operator sees the refusal. Surviving constraint: **`running` stays the last assignment of the start path** | `TestRunnerThread#startThread` |
| ~~H2~~ | **Closed**: the stop path reads `test`, its context and the runner thread into locals and stops the motor directly when any of them is absent. Surviving constraint: **the stop reads locals, never the fields** | `TestRunnerThread#stopThread` |
| ~~H3~~ | **Closed**: `startThread` is `synchronized`, so the `running` check-then-act cannot interleave. Surviving constraint: **the start is synchronized and one bench serves one run** | `TestRunnerThread#startThread` |
| H4 | Mid-`setup()` failure cascades: `cleanup()` NPEs on null `cfw11`, refcount corruption, orphaned `LoadCellThread` holding the CSV file | `deck/testrunner/TestRunnerThread.java:51`, `deck/testrunner/AbstractTest.java:57` |
| H5 | Stop commands fire-and-forget — no read-back verification of motor state anywhere | `deck/testrunner/AbstractTest.java:52` |
| H6 | `Cfw11` driven concurrently by info-broadcast polling thread and test thread with no locking; `DeviceInfoService` disable race can close the CFW11 USB connection mid-test | `deck/device/frequencyinverter/FrequencyInverterDevice.java:249`, `deck/api/services/DeviceInfoService.java:31` |
| H7 | `sendSignal` dedup + direction-guarded handling can permanently swallow a limit crossing in cyclic mode | `deck/testrunner/TestContext.java:45` |
| H8 | No validation of configured limits/speed (null, inverted, beyond machine rating go straight to the drive) | `cms .../data/TestParameter.java` |
| H9 | `start()`/`stop()` are `@PermitAll` with no ownership or role check on the `TestResult`. The void-success half is **Closed**: a start that fails throws, and Hilla reports it | `deck/api/services/TestRunnerService.java:22` |
| H10 | Test outcome never persisted: aborted/crashed runs indistinguishable from completed ones | `cms .../data/TestResult.java` |
| H11 | Frontend Stop is fire-and-forget (UI shows stopped even if the call failed); browser disconnect never detected; no staleness indicator, frozen force renders as live | `.../components/dashboard/LiveTestResult.tsx:149` |

## Medium / low — worth fixing opportunistically

| Finding | Anchor |
|---|---|
| Unsynchronized min/max read-modify-write corrupts cyclic adaptive-limit compensation | `deck/testrunner/LoadCellThread.java:97` |
| CSV flushes only on 8 KB boundaries — break-event tail lost on crash | `deck/testrunner/LoadCellThread.java:100` |
| ~~`TestLogger` never closed on a natural finish~~ — **Closed**: the runner's `finally` ends the logger and `end()` is idempotent | `TestRunnerThread#run` |
| Peak extraction discards values ≥ 300 kN; a file under 100 lines yields no peak and a row that is not `<timestamp>,<number>` is skipped | `CSVStoreService#getPeakFromResultFile` |
| `FourWayRelaySwitch` swallows send errors and relay state is never read back. `SuckJob` logs a refused off-write as an error; that log line is the whole remedy, relay 1 may still be energized | `deck/device/relayswitch/FourWayRelaySwitch.java:76`, `SuckJob#suck` |
| ~~Unknown test type silently no-ops~~ — **Closed**: the `default` branch throws and the run settles FAULT naming the type | `TestRunnerThread#run` |
| `stopThread` untimed `join()` can hang the request thread; 1 s first join can interrupt motor-disable cleanup | `deck/testrunner/TestRunnerThread.java:84` |
| ~~Subscription leak per render of the execute-test view~~ — **closed**; both it and the twin in `@index.tsx` now subscribe from a `useEffect` that unsubscribes | `.../views/.../run.tsx`, `.../views/@index.tsx` |
