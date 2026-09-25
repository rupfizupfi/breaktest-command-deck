> Branch: `dev-split` — refreshed 2026-08-17.

# Tasks — hardware and driver repos

Split out of [`TASKS.md`](TASKS.md), which owns the rest and states the shared
conventions. **What** is open and **why** is in
[`../../OPEN-QUESTIONS.md`](../../OPEN-QUESTIONS.md); this file is only *how*.

The last three items are work in the **sibling repos** (`dscusb`, `usbmodbus`),
not in this one — see [`../../03-backend/driver-jars.md`](../../03-backend/driver-jars.md).

## Contents

- [[x] OQ-45 · Reconnect on load-cell loss](#x-oq-45--reconnect-on-load-cell-loss)
- [[~] OQ-44 · Device identity for the inverter check](#-oq-44--device-identity-for-the-inverter-check)
- [[ ] OQ-50 · Investigate the dual `Cfw11` handle](#--oq-50--investigate-the-dual-cfw11-handle)
- [[ ] OQ-43 · Document `usbmodbus.jar` procurement — **owner-owed**](#--oq-43--document-usbmodbusjar-procurement--owner-owed)
- [[x] OQ-74 · Decide whether one bad sample should end the run](#x-oq-74--decide-whether-one-bad-sample-should-end-the-run)

### [x] OQ-45 · Reconnect on load-cell loss
**Shipped 2026-08-29.** The owner supplied the resume policy that blocked it; the numbers are now
`RecoveryProperties` defaults rather than literals, snapshotted per run so a result stays auditable.
Design of record, including what shipped in which file:
[`../testrunner-safety/loadcell-recovery-design.md`](../testrunner-safety/loadcell-recovery-design.md).
The gap is represented three ways — an in-band epoch-millis discontinuity in the untouched force CSV,
a `<millis>_gaps.json` sidecar, and `TestResult.runStatus` = `COMPLETED_WITH_GAPS` + `interruptionLog`.

**Not verified on hardware.** The three recovery fault switches
([`fault-injection.md`](../virtual-devices/fault-injection.md)) exercise the branches; the bench does not.

### [~] OQ-44 · Device identity for the inverter check
- **Done:** `FrequencyInverterCheck` (`command-deck/src/main/java/ch/rupfizupfi/deck/testrunner/startup/check/FrequencyInverterCheck.java`) connects, reads the control parameters through the shared drive handle, writes nothing, and is registered in `TestRunnerFactory.getStartupChecks()` after `LoadCellCheck`.
- **Left:** the check proves *something answers*, not *what*. A simulated device must return a distinguishable identity (serial, model), or the check passes against a fake — design it with the simulator (OQ-62).

### [ ] OQ-50 · Investigate the dual `Cfw11` handle
- **File:** `command-deck/src/main/java/ch/rupfizupfi/deck/testrunner/TestRunnerThread.java` (`retryShutdownOnException`)
- **Question:** is opening a second `Cfw11` on the same USB device safe while the service-managed instance may still be open?
- **Deliverable:** a documented finding, then either reuse the managed instance or an inline comment explaining why a fresh handle is correct. This is the emergency-stop path — do not "clean it up" without the finding.

### [ ] OQ-43 · Document `usbmodbus.jar` procurement — **owner-owed**
- **File:** [`../../03-backend/driver-jars.md`](../../03-backend/driver-jars.md)
- **Needed from the owner:** vendor/source, licence holder, required version, and how a new developer legitimately obtains it.
- **Also worth answering:** the redistribution blocker is the bundled vendor jars (`CommunicationLib.jar`, `ThesyconUSBLib.jar`), not the `ch.rupfizupfi.usbmodbus` code — is splitting them permitted, so the project half can be committed?

### [x] OQ-74 · Decide whether one bad sample should end the run
**The fork went the second way: drop and continue, bounded.** Shipped in `dscusb` 0.3.0.

- **Where the policy lives:** `CommandExecutionException.isTransient` classifies the fault;
  `CellValueStream`'s reader loop decides what to do about one. `DSCUSB.readCommand` is
  **unchanged** — it still seeds `NaN` and still throws `-800`. Only one caller's response changed.
- **The bound is wall clock, not a retry count** (`DROP_BUDGET_MS = 80`), because the loop does not
  sleep and reads at DLL speed, so N retries is an unpredictable amount of missing data. What has to
  be bounded is the length of the hole. Past the budget the *original* exception is rethrown
  unwrapped, so the deck still names `driver error -800`.
- **Terminal codes keep the old behaviour**: `-1`, `-2`, `-100`, `-400`, and anything unknown. The
  `-100` call is the interesting one and its evidence is recorded in
  [`dscusb`'s return-code table](../../03-backend/driver-jars.md#dscusbjar--load-cell).
- **New observable:** `LoadCellStream.droppedSampleCount()`, a `default` method — so **device-api
  1.0.0 → 1.1.0**, minor per the documented bump rules, and **dscusb 0.2.0 → 0.3.0**.
- **Merge order is forced.** Deck PR **first** — `dscusb`'s CI checks out the deck's `main` as its
  composite build, so the 1.1.0 override would not resolve otherwise — then `dscusb`. And the deck
  must be **redeployed before** the 0.3.0 jar is dropped in: `verifyPluginBuiltAgainst` refuses a
  plugin built against a newer minor.
- **Both halves are written, neither is merged:** the contract and deck side on the deck's
  `feat/loadcell-recovery`, the 80 ms budget on `dscusb`'s `feat/deck-plugin`. The order above
  still governs.
- **Residual risk, filed as OQ-81:** only *consecutive* faults are budgeted.
- **Still unverified on the bench.** The deck half is exercised by `LOAD_CELL_DROPPED_SAMPLES`; the
  80 ms budget itself is `dscusb` code the simulator never reaches.

