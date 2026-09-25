# Feature work: test-runner safety hardening

**Goal / guarantee:** loss of force feedback (or any single software fault) must never leave the
motor driving blind. Detection of load-cell loss ≤ ~450 ms, verified motor stop, operator-visible
incident state, and a validated resume path where scientifically defensible.

Source: 14-agent safety audit (2026-08-16) of the test execution engine
([`doc/03-backend/test-execution-engine.md`](../../03-backend/test-execution-engine.md)) —
77 findings, top criticals adversarially verified including bytecode analysis of the `dscusb` driver jar.

## Files

| File | Purpose |
|---|---|
| `README.md` | This index + phased plan |
| `audit-findings.md` | Verified findings by angle, with code anchors |
| `loadcell-recovery-design.md` | Full load-cell disconnect detection/safe-stop/recovery design |
| `staleness-and-lifecycle-findings.md` | Second audit: stale-value and accumulation findings still open |

## Phases (priority order)

| Phase | Scope | Status |
|---|---|---|
| 2 | `TestRunnerThread` lifecycle hardening: NPE-safe stop, wedged-`running` fix, synchronized start, error propagation to caller | `[x]` |
| 3 | State machine + persistence (`TestResult.runStatus`, `interruptionLog`, `StartupRecoveryRunner`) + operator incident UI with resume/abort | `[x]` |
| 4 | Limit validation, intra-batch peak checking, ownership checks on start/stop, CSV flushing | `[ ]` |

Phase 4 is defense in depth.

Phase 3 **shipped 2026-08-29** (OQ-45), against the owner's resume policy rather than the invented
numbers the design first carried. Two live safety defects were fixed on the way and are worth naming
because both left the motor driving: `minValue`/`maxValue` were folded from *every* sample before the
plausibility vote, so one NaN made both NaN, poisoned `CyclicTest`'s force limits and stopped PULL and
RELEASE from ever firing again; and `TestContext.sendSignal` deduplicated signal 0, so the watchdog's
stop swallowed the operator's Stop. Detail in
[`loadcell-recovery-design.md`](loadcell-recovery-design.md).

## Phase-1 follow-up: landed

The frontend staleness indicators (`5e17393`) now do their job, and have been watched doing it on
the simulated bench: `/control` shows a live `0.27 kN`, and stopping the broadcast with the socket
still open turns that reading into `0.39 kN (2 s ago)` in amber under "no force data for 2 s — this
reading is not live", the age counting up each second. InfoBoard takes both device topics.
`/deck-run` is 11/11 + crud-smoke.

Two defects stood between the commit and that demonstration, both now fixed. The STOMP handshake
was refused **403** for authenticated operators (ex-OQ-79): `VaadinSecurityConfigurer` closes the
filter chain with `anyRequest().denyAll()`, and `/status` — neither Vaadin route nor Hilla endpoint
— had no rule of its own, so the bench broadcast into an empty room. `SecurityConfiguration` now
grants it `authenticated()`.

The blank `/` this section tracked as OQ-65 **does not reproduce** and the row is gone. The
recorded suspicion — a render loop between `@index.tsx`'s per-render subscribe and `InfoBoard`'s
`useLiveStatus()` — was measured and refused: driving a 60 ms feed into the unfixed view for 60 s
grew the leak to 1005 subscriptions with the page still rendering and zero console errors. The
symptom was also structurally inconsistent with the suspect, since `InfoBoard` renders in
`@layout.tsx` and would have taken *every* route down, not `/` alone. The leak itself was real and
is fixed (`useEffect` + unsubscribe in `@index.tsx` and `run.tsx`, ex-OQ-24).

The `dscusb` NaN rejection **landed** (2026-08-17): the driver was modernised to Gradle 9.7 /
Kotlin 2.4.10 / JVM 26 and rebuilt. `LoadCellDevice` and `LoadCellThread` consume its
`isReading()` / `lastError()` to name the driver's own cause in a trip reason. Its cost — one
non-finite reading ending the run — was OQ-74, and is now closed the other way: `dscusb` 0.3.0
drops a *transient* fault and reads on within an 80 ms budget, counted through
`droppedSampleCount()`.

**Not verifiable end-to-end right now — the bench hardware is not attached** (`LoadCellCheck`
refuses to start a run without a fresh measurement; the dev-side answer is OQ-62).

## Open questions touched by this work

| OQ | Relation |
|---|---|
| OQ-81 unbounded dropped fraction | The residual risk left by OQ-74's answer: only consecutive faults are budgeted. Needs the bench |
| OQ-44 hardware presence checks | `FrequencyInverterCheck` follows the same pattern as `LoadCellCheck`; the device-identity half is still owed |
| OQ-50 dual drive handle on one device | `MotorSafetyController` tier 2 reuses the pattern via `DriveProvider.open()` — the USB dual-open finding is still owed |
