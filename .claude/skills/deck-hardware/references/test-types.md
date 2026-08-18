# Adding a test type

Engine lives in `command-deck/src/main/java/ch/rupfizupfi/deck/testrunner/`.

## Required edits

```
- [ ] 1. XTest extends AbstractTest (or CyclicTest) in testrunner/
- [ ] 2. New case in the switch in TestRunnerThread.run()
- [ ] 3. TestParameter: new @Nullable columns if needed (cms data/TestParameter.java)
- [ ] 4. View cms/src/main/frontend/views/test/x.tsx
- [ ] 5. Startup checks if needed (TestRunnerFactory.getStartupChecks())
```

## 1. The test class contract

- Implement `setup()`; the engine then blocks in `getContext().processSignals()`.
- You receive signals via `handleSignal(int)` (`AbstractTest implements SignalListener`). Signal semantics from `TestContext`: `RELEASE_SIGNAL = 1`, `PULL_SIGNAL = 2`, `0` = stop. `LoadCellThread` is the signal source — it compares each measurement against `testContext.upperLimit`/`lowerLimit` and sends the corresponding signal; repeats are deduped.
- End the test by calling `finish()` — it runs `cleanup()` and **throws `FinishTestException`**, which `TestRunnerThread` catches as the normal exit path. Never swallow that exception.
- `cleanup()` **can run twice** (from `finish()` and from the `finally` block) — make it idempotent.
- Motor direction helpers: `cfw11Pull()`, `cfw11Release()`, `cfw11IsPull()`, `cfw11IsRelease()`. Log via `log()` → goes to `/topic/logs` and the per-run log file.
- Constructor injection is reflection-based (`TestRunnerFactory`): `TestResult`, `TestLogger`, `TestRunnerFactory` are passed literally; any other constructor param is resolved via `applicationContext.getBean(type)`. Only the **first public constructor** is used.
- Cycle-based tests: extend `CyclicTest` / use `cyclic/CyclicTestContext` (holds `cycleCount`, auto-sends stop at zero).

## 2. Type dispatch

`TestRunnerThread.run()` hard-codes the mapping:

```java
test = switch (testResult.testParameter.type) {
    case "cyclic" -> ...; case "timeCyclic" -> ...; case "destructive" -> ...;
```

Add your `case "<type>"`. The string must exactly match what the frontend writes into `TestParameter.type`. An unknown type currently falls through to `default -> test` (null) and the run silently does nothing — verify your case is hit.

## 4. Frontend view

Copy `cms/src/main/frontend/views/test/destructive.tsx`:
- `constraintServiceToFilter(TestParameterService, {propertyId: 'type', filterValue: '<type>', matcher: Matcher.EQUALS, '@type': 'propertyString'})` scopes the grid.
- Subclass `TestParameterModel` and `static override createEmptyValue()` to pre-set `type: '<type>'`.
- Pass the visible parameter fields to `buildAutoCrud(...)` (from `cms/components/autocrud/test`).
- `ViewConfig` uses `menu: {exclude: true}` — test views are linked from the test index, not the SideNav.

## 5. Startup checks

Pre-run validation classes extend `startup/check/AbstractCheck` and throw `CheckFailedException`. They are **not auto-discovered**: `TestRunnerFactory.getStartupChecks()` returns a hard-coded array — add yours there.

## Runtime facts worth knowing

- One global `TestRunnerThread` per app (held by `TestRunnerService`); `startThread()` no-ops if a test is running. No cross-browser locking beyond that.
- `LoadCellThread` writes `timestamp,force` CSV via `CSVStoreService`; logs and CSV land under `StorageLocationService.getResultDataLocation()/<testResultId>/`.
- `run()` starts with `Thread.sleep(50)` to let the client attach the WebSocket — a race, not a handshake; don't rely on the first log lines reaching the browser.
- On cleanup failure the engine calls `retryShutdownOnException()` which force-stops the motor via a fresh `Cfw11`. Keep that path dependency-free.
