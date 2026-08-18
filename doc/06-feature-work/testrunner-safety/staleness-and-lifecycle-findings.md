# Staleness and lifecycle findings

Second audit pass (2026-08-17), run after phases 0–1 landed and scoped to a question the first
audit did not ask: **can a value be presented as current after the thing producing it stopped, and
does anything accumulate across a multi-week session?**

Anchors are `File#member`, not line numbers — these move. Items already owned by
[`audit-findings.md`](audit-findings.md) (C5 shutdown hook, H1/H2 lifecycle, H11 fire-and-forget
Stop, `TestLogger` never closed, the per-render subscribe) are not repeated here.

## Stale data reaching control logic

The no-data watchdog in `LoadCellThread` closes the silent-sensor case. What it does not close:

| Finding | Anchor | Note |
|---|---|---|
| A feed that is slow but not silent passes every detector — the no-data timeout is 250 ms, the frozen detector needs 100 bit-identical samples | `LoadCellThread#noDataTimedOut`, `#inspectSample` | Limit checks run on whatever the trickle delivers |
| ~~NaN poisons `minValue`/`maxValue`~~ — **closed** at source; the driver now throws on a non-finite reading and the jar shipped (`ec47aa6`) | `LoadCellThread#run` | Traded for OQ-74: the throw kills the stream, so one bad sample ends the run |
| Limit check still inspects only `measurements.getLast()` — intra-batch peaks never reach the shut-off | `LoadCellThread#run` | Original audit C7; phase 4 |

## Stale data reaching the operator

Frontend staleness indicators are live and verified on the simulated bench. Independent of them:

| Finding | Anchor |
|---|---|
| `Info` carries no timestamp; the only liveness tell is `idProvider`, rendered unlabelled as `Status: {info.id}` | `CFW11Device#readData`, `InfoBoard.tsx` |
| After a tier-2 escalation the drive handle is intentionally null, so the poll loop throws and skips every round while subscribers keep rendering the last `Info` | `CFW11Device#readData`, `MotorSafetyController#stopWithFreshHandle` |
| `ForceBroadcaster` only flushes when a *new* batch arrives, so the last ≤60 ms of a run is stranded and re-broadcast on the first sample of the next run | `ForceBroadcaster#update` |
| Browser joining mid-test anchors its x-axis to `Date.now()`, presenting a four-minute-old run as starting at t=0 | `LiveTestResult.tsx` — `TestResultGraph` |
| No test-state feed: the UI cannot distinguish running / finished / aborted / faulted | phase 3 |

## Lifecycle and leaks

| Finding | Anchor | Cost |
|---|---|---|
| `LoadCellThread` runs forever if `cleanup()` throws before `setRunning(false)` — `log()` can throw `MessageDeliveryException`, `stop()` has **no callers**, and nothing joins the thread | `AbstractTest#cleanup`, `LoadCellThread#stop` | One thread + one open CSV writer + a pinned load-cell reference, per occurrence |
| Nothing joins an outgoing `LoadCellThread`, so whether a run pays a full load-cell USB close/re-enumerate is decided by a ~20–100 ms race | `AbstractTest#cleanup`, `TestRunnerThread#run` | Nondeterministic, not corrupting |
| Poll thread abandoned by the bounded join can be resurrected by a later `tryStartThread()` — `isRunning` is one flag for what may be N threads, and `idProvider++` is a plain non-volatile `int` | `CFW11Device#tryStopThread`, `#readData` | Duplicate/regressing `info.id`; a stale frame can arrive after a fresh one |
| `DeviceInfoService.isEnabled` is one process-global flag, not per-client | `DeviceInfoService` | One operator closing their dashboard starves every other tab |
| `System.gc()` in `retryShutdownOnException` runs **between** nulling `test` and calling `safeStop` | `TestRunnerThread#retryShutdownOnException` | A stop-the-world pause inserted into the emergency stop |
| `System.gc()` in `destroy()` is the only thing reclaiming the leaked `TestLogger` descriptors — removing it exposes that leak | `AbstractTest#destroy`, `TestRunnerThread#stopThread` | Fix `end()` first, then delete both |

## Introduced by phase 1 — accepted or owed

| Item | Anchor | Status |
|---|---|---|
| `markConnectionLost()` zeroes the reference count including holders that still exist, so the *next* run's `disconnect()` reaches 0 and closes a handle the dashboard is using | `Device#markConnectionLost` | Owed — OQ-68 |
| `safeStop` can hold `driveLock` for the full 5 s verification, stalling the info poll and any Hilla thread in `DeviceInfoService.disable()` | `MotorSafetyController#safeStop` | Accepted: verification is the point, and the tier-2 gate keeps it off the routine path |
| ~~`getHardwareComponent()` bypassed the drive lock~~ | — | **Closed**: deleted from both devices and from `Device`; `withDrive`/`queryDrive` are the only doors |
| `Device` javadoc claims "It holds data to avoid repeated requests from the hardware" — nothing is cached anywhere | `Device` | Wrong, and it misleads exactly this kind of audit. Delete the line |
| `LoadCellCheck` refuses to start a run without a fresh measurement, so no test can run on a machine with no sensor attached | `LoadCellCheck#execute` | Intended on the bench; the dev-side answer is the simulator (OQ-62) |

## Links

- [`README.md`](README.md) — phases and in-flight state
- [`audit-findings.md`](audit-findings.md) — first audit, 2026-08-16
- [`../../03-backend/hardware-integration.md`](../../03-backend/hardware-integration.md)
- [`../../04-frontend/state-and-realtime.md`](../../04-frontend/state-and-realtime.md)
