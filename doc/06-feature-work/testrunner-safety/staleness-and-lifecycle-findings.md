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
| ~~NaN poisons `minValue`/`maxValue`~~ — **closed twice**: the driver rejects non-finite readings, and the envelope now updates only past the plausibility check | `LoadCellThread#inspectSample` | The driver fix alone was not enough — a finite-but-absurd reading did the same damage arithmetically, and `dscusb` only rejects non-finite values |
| ~~Limit check inspects only the last sample of a drain~~ — **closed**: every sample is held against the limits, the earliest crossing decides and one signal is sent per drain | `LoadCellThread#run` | Original audit C7 |

## Stale data reaching the operator

Frontend staleness indicators are live and verified on the simulated bench. Independent of them:

| Finding | Anchor |
|---|---|
| `Info` carries no timestamp; the only liveness tell is `idProvider`, rendered unlabelled as `Status: {info.id}` | `FrequencyInverterDevice#readData`, `InfoBoard.tsx` |
| After a tier-2 escalation the drive handle is intentionally null, so the poll loop throws and skips every round while subscribers keep rendering the last `Info` | `FrequencyInverterDevice#readData`, `MotorSafetyController#stopWithFreshHandle` |
| ~~`ForceBroadcaster` only flushes when a *new* batch arrives, so the last ≤60 ms of a run is stranded and re-broadcast on the first sample of the next run~~ — **closed**: the device closing flushes every observer, so the tail reaches the chart that produced it (OQ-72) | `LoadCellDevice#closeConnection`, `ForceBroadcaster#flush` |
| Browser joining mid-test anchors its x-axis to `Date.now()`, presenting a four-minute-old run as starting at t=0 | `LiveTestResult.tsx` — `TestResultGraph` |
| No test-state feed: the UI cannot distinguish running / finished / aborted / faulted | phase 3 |

## Lifecycle and leaks

| Finding | Anchor | Cost |
|---|---|---|
| ~~`LoadCellThread` runs forever if `cleanup()` throws before `setRunning(false)`~~ — **closed** (OQ-69): `log()` absorbs a rejected STOMP broadcast, `end()` claims the writer in one read and is idempotent, and `cleanup()` calls `stop()`, which interrupts and joins | `AbstractTest#cleanup`, `LoadCellThread#stop`, `TestLogger#log` | Was one thread + one open CSV writer + a pinned load-cell reference, per occurrence |
| Nothing joins an outgoing `LoadCellThread`, so whether a run pays a full load-cell USB close/re-enumerate is decided by a ~20–100 ms race | `AbstractTest#cleanup`, `TestRunnerThread#run` | Nondeterministic, not corrupting |
| ~~Poll thread abandoned by the bounded join can be resurrected by a later `tryStartThread()` — `isRunning` is one flag for what may be N threads, and `idProvider++` is a plain non-volatile `int`~~ — **closed** (OQ-67): each poll thread exits on its own flag and delivers only while that flag is set, and `idProvider` is an `AtomicInteger` | `FrequencyInverterDevice#tryStopThread`, `#readData` | Was duplicate/regressing `info.id` and a stale frame arriving after a fresh one |
| `DeviceInfoService.isEnabled` is one process-global flag, not per-client | `DeviceInfoService` | One operator closing their dashboard starves every other tab |
| ~~`System.gc()` in `retryShutdownOnException`~~ | — | **Closed**: deleted, and the retry logs the failure instead of swallowing it |
| ~~`System.gc()` in `destroy()` reclaimed the leaked `TestLogger` descriptors~~ | — | **Closed**: `run()` ends the logger on every exit, so both calls are gone |

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
