> Branch: `dev-split` — refreshed 2026-08-17.

# Tasks — hardware and driver repos

Split out of [`TASKS.md`](TASKS.md), which owns the rest and states the shared
conventions. **What** is open and **why** is in
[`../../OPEN-QUESTIONS.md`](../../OPEN-QUESTIONS.md); this file is only *how*.

The last three items are work in the **sibling repos** (`dscusb`, `usbmodbus`),
not in this one — see [`../../03-backend/driver-jars.md`](../../03-backend/driver-jars.md).

## Contents

- [[ ] OQ-45 · Reconnect on load-cell loss](#--oq-45--reconnect-on-load-cell-loss)
- [[ ] OQ-46 · Externalise the relay port description](#--oq-46--externalise-the-relay-port-description)
- [[ ] OQ-44 · Add `Cfw11Check`](#--oq-44--add-cfw11check)
- [[ ] OQ-50 · Investigate the dual `Cfw11` handle](#--oq-50--investigate-the-dual-cfw11-handle)
- [[ ] OQ-43 · Document `usbmodbus.jar` procurement — **owner-owed**](#--oq-43--document-usbmodbusjar-procurement--owner-owed)
- [[ ] OQ-74 · Decide whether one bad sample should end the run](#--oq-74--decide-whether-one-bad-sample-should-end-the-run)

### [ ] OQ-45 · Reconnect on load-cell loss
- **Files:** `command-deck/.../device/loadcell/` (`LoadCellThread`, `CellValueStream`)
- **Change:** detection already trips a safe stop (`LoadCellThread`'s no-data / frozen / plausibility watchdogs). What is missing is the recovery: attempt reconnection and resume, aborting only if the device can't be recovered within a bounded window.
- **Unspecified, and *not* a decide-while-implementing call:** the window length and how the result record represents the gap. Along with the other resume numbers in [`../testrunner-safety/loadcell-recovery-design.md`](../testrunner-safety/loadcell-recovery-design.md#recovery-and-resume), these decide whether a resumed run's data is publishable — owner-owed.

### [ ] OQ-46 · Externalise the relay port description
- **File:** `command-deck/src/main/java/ch/rupfizupfi/deck/device/relayswitch/FourWayRelaySwitch.java:19`
- **Change:** `@Value("${device.relay.port-description:CH9102}")` instead of the inline `contains("CH9102")` literal; add the default to `cms/src/main/resources/application.properties`.

### [ ] OQ-44 · Add `Cfw11Check`
- **Path:** `command-deck/src/main/java/ch/rupfizupfi/deck/testrunner/startup/check/`
- **Change:** an `AbstractCheck` subclass that probes the frequency converter and fails with a clear message; register it in `TestRunnerFactory.getStartupChecks()` next to `FileSystemCheck` and `LoadCellCheck`, whose shape it should follow.
- **Design together with the simulator (OQ-62):** a simulated device must return a *distinguishable* identity, or the check passes against a fake.

### [ ] OQ-50 · Investigate the dual `Cfw11` handle
- **File:** `command-deck/src/main/java/ch/rupfizupfi/deck/testrunner/TestRunnerThread.java` (`retryShutdownOnException`)
- **Question:** is opening a second `Cfw11` on the same USB device safe while the service-managed instance may still be open?
- **Deliverable:** a documented finding, then either reuse the managed instance or an inline comment explaining why a fresh handle is correct. This is the emergency-stop path — do not "clean it up" without the finding.

### [ ] OQ-43 · Document `usbmodbus.jar` procurement — **owner-owed**
- **File:** [`../../03-backend/driver-jars.md`](../../03-backend/driver-jars.md)
- **Needed from the owner:** vendor/source, licence holder, required version, and how a new developer legitimately obtains it.
- **Also worth answering:** the redistribution blocker is the bundled vendor jars (`CommunicationLib.jar`, `ThesyconUSBLib.jar`), not the `ch.rupfizupfi.usbmodbus` code — is splitting them permitted, so the project half can be committed?

### [ ] OQ-74 · Decide whether one bad sample should end the run
- **Where:** sibling `dscusb` repo (`DSCUSB.readCommand`, `CellValueStream`), not this one
- **Behaviour today:** a non-finite reading throws, the reader loop exits and closes the port, the stream stops permanently, and the deck safe-stops on the 250 ms no-data timeout.
- **The fork:** keep it (an untrustworthy reading must never reach limit logic, and a lost run beats a corrupted one), or drop just the bad sample and let the loop continue — reserving stream death for errors that really are terminal.
- **Note:** whichever way it goes, OQ-45's reconnect-and-resume is what bounds the cost.
- **Verify:** needs the bench, or the OQ-62 simulator's NaN-injection switch.

