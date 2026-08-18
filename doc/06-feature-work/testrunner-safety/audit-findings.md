# Audit findings — test-runner safety (2026-08-16)

Verified against source; criticals additionally adversarially verified (none refuted).
Paths relative to repo root; `deck/` = `command-deck/src/main/java/ch/rupfizupfi/deck/`.

## Critical — motor can drive blind

| # | Finding | Anchor |
|---|---|---|
| ~~C2~~ | **Closed** by the driver rebuild (`ec47aa6`): the reader now catches, closes the port and records the cause, and `running` goes false so a dead reader stops claiming to be alive. Surviving constraint: **a stopped stream cannot be restarted** — reconnection must build a new `CellValueStream` | `LoadCellDevice#getStreamFailure` |
| C5 | No shutdown hook / watchdog: JVM kill or Spring stop mid-test leaves motor to the drive's own comm-error action (`setActionInCaseOfCommunicationError(2)`) — which only covers the **CFW11 link**, not load-cell loss | `deck/testrunner/DestructiveTest.java:24` |
| C6 | Last-resort stop `retryShutdownOnException` can itself throw and escape; no alternate kill path | `deck/testrunner/TestRunnerThread.java:115` |
| C7 | Limit check inspects only the **last** measurement of each drained batch; intra-batch peaks never reach the shut-off | `deck/testrunner/LoadCellThread.java:104` |

## High — lifecycle & control path

| # | Finding | Anchor |
|---|---|---|
| H1 | `startThread` sets `running=true` before `testLogger.begin()`; `IOException` wedges the service permanently (phantom running test, stop NPEs) | `deck/testrunner/TestRunnerThread.java:64` |
| H2 | `stopThread` NPEs while `test` is still null (startup window, failed setup) — **the emergency stop can throw** | `deck/testrunner/TestRunnerThread.java:82` |
| H3 | `startThread` check-then-act race: concurrent `start()` calls can spawn two motor-driving tests | `deck/testrunner/TestRunnerThread.java:65` |
| H4 | Mid-`setup()` failure cascades: `cleanup()` NPEs on null `cfw11`, refcount corruption, orphaned `LoadCellThread` holding the CSV file | `deck/testrunner/TestRunnerThread.java:51`, `deck/testrunner/AbstractTest.java:57` |
| H5 | Stop commands fire-and-forget — no read-back verification of motor state anywhere | `deck/testrunner/AbstractTest.java:52` |
| H6 | `Cfw11` driven concurrently by info-broadcast polling thread and test thread with no locking; `DeviceInfoService` disable race can close the CFW11 USB connection mid-test | `deck/device/frequencyconverter/CFW11Device.java:76`, `deck/api/services/DeviceInfoService.java:31` |
| H7 | `sendSignal` dedup + direction-guarded handling can permanently swallow a limit crossing in cyclic mode | `deck/testrunner/TestContext.java:45` |
| H8 | No validation of configured limits/speed (null, inverted, beyond machine rating go straight to the drive) | `cms .../data/TestParameter.java` |
| H9 | `start()`/`stop()` are `@PermitAll` with no ownership or role check on the `TestResult`; `start()` returns void success even when checks fail | `deck/api/services/TestRunnerService.java:22` |
| H10 | Test outcome never persisted: aborted/crashed runs indistinguishable from completed ones | `cms .../data/TestResult.java` |
| H11 | Frontend Stop is fire-and-forget (UI shows stopped even if the call failed); browser disconnect never detected; no staleness indicator, frozen force renders as live | `.../components/dashboard/LiveTestResult.tsx:149` |

## Medium / low — worth fixing opportunistically

| Finding | Anchor |
|---|---|
| Unsynchronized min/max read-modify-write corrupts cyclic adaptive-limit compensation | `deck/testrunner/LoadCellThread.java:97` |
| CSV flushes only on 8 KB boundaries — break-event tail lost on crash | `deck/testrunner/LoadCellThread.java:100` |
| `TestLogger` never closed when a test finishes naturally (`end()` only runs from `stopThread`) | `deck/testrunner/TestRunnerThread.java:48` |
| Peak extraction discards values ≥ 300 kN and fabricates 0.0 for old files | `deck/filesystem/CSVStoreService.java:74` |
| `FourWayRelaySwitch` swallows send errors, never verifies relay state; `SuckJob` can leave relay 1 energized | `deck/device/relayswitch/FourWayRelaySwitch.java:76` |
| Unknown test type silently no-ops (`default -> test` yields null) | `deck/testrunner/TestRunnerThread.java:32` |
| `stopThread` untimed `join()` can hang the request thread; 1 s first join can interrupt motor-disable cleanup | `deck/testrunner/TestRunnerThread.java:84` |
| ~~Subscription leak per render of the execute-test view~~ — **closed**; both it and the twin in `@index.tsx` now subscribe from a `useEffect` that unsubscribes | `.../views/.../run.tsx`, `.../views/@index.tsx` |
