# Simulated bench — running a test with no hardware attached

Enables a full `destructive` / `cyclic` run on a developer machine with no load cell, drive or
relay, and makes the safety paths trippable on demand. A healthy run exercises only tier 1 of
`MotorSafetyController` (the `safeStop("test cleanup")` every run ends with); tier 2 USB
re-enumeration, tier 3 operator escalation and all four `MeasurementLoop` detectors fire only
during an incident.

Device internals are owned by
[`hardware-integration.md`](hardware-integration.md);
watchdog semantics by
[`safety-and-recovery.md`](safety-and-recovery.md).

## Contents

- [Mode contract](#mode-contract)
- [Why the obvious fake cannot work](#why-the-obvious-fake-cannot-work)
- [One plant model, both fakes read it](#one-plant-model-both-fakes-read-it)
- [Seams to extract](#seams-to-extract)
- [Fault injection — the reason to build this](#fault-injection--the-reason-to-build-this)
- [What the mode separation does not cover](#what-the-mode-separation-does-not-cover)
- [Where to look in the code](#where-to-look-in-the-code)
- [Known limits](#known-limits)

## Mode contract

Simulation never reaches the bench, so no per-run "simulated" marker is stored in the database.
The result *files* need separate handling — see
[below](#what-the-mode-separation-does-not-cover).

`deck.hardware.mode` is enforced at startup by `HardwareModeCheck`
([`device-api.md`](device-api.md#startup-contract)); the full mode table
is in [`spring-boot-setup.md`](../modules/spring-boot-setup.md#hardware-mode):

| Deployment | Hardware |
|---|---|
| `dev` | simulated by default; `deck.hardware.mode=real` opts into hardware |
| deck container (`docker`) | simulated (`DECK_HARDWARE_MODE=simulated`) — the tests-and-simulations deployment |
| native bench (`bench` + `docker`, via `script/run-bench.ps1`) | real; `simulated` is refused |
| anything else | `real` (the base default); `simulated` is refused |

Simulation is allowlisted: it is permitted only when every active profile is in
`SIMULATION_PROFILES` (`dev`, `docker`), and the refusal happens before the datasource is touched.

Two rules on top:

- **No fallback in either direction.** Absent hardware must never select the simulator — that
  makes "unplugged" indistinguishable from "working". A requested simulator must never fall
  back to hardware.
- **Annunciation that cannot be silenced**: a WARN line naming the mode at every test start, to
  the server log and the operator's test log, plus a persistent UI banner while simulated. The
  banner reads its own `HardwareModeService` and shares no state with `StatusService` /
  `useLiveStatus`, so a realtime fault cannot silence it.

The `real` override on `dev` exists because `./gradlew :command-deck:bootRun` is the documented
way to run the app, so without it hardware could never be driven interactively.

`command-deck/src/main/resources/application.properties` sets `spring.profiles.default=dev`, so
an unset `SPRING_PROFILES_ACTIVE` resolves to the simulated state.

## Why the obvious fake cannot work

A fake returning a counter, a constant, or `System.currentTimeMillis()` as force is
**killed by the existing watchdogs before the first threshold is reached**, and every
"fix" for that is a loosened safety constant. Hence the plant model below.

| Detector | Constant | What a naive fake does |
|---|---|---|
| Plausibility | `command-deck/src/main/java/ch/rupfizupfi/deck/testrunner/measure/MeasurementLoop.java` rejects \|F\| > 1.5 × the run's rated capacity (`RigLimits`) | a ms timestamp is ~1.7 × 10¹² N → 3-of-5 vote → `sensorLost` → `safeStop`, run over |
| Frozen value | `MeasurementLoop.java`, 100 bit-identical samples | a *constant* force trips it; a live strain gauge always dithers, so the fake must add LSB noise |
| Implausible rise | `MeasurementLoop.java`, gain > half the rated capacity between samples | a clock-derived ramp trips it |
| No-data timeout | `MeasurementLoop.java`, 250 ms | the fake must keep delivering, not just answer once |

Two silent-corruption traps beyond the watchdogs:

- `ForceBroadcaster.java:20` compares wall clock against `Measurement.timestamp()`,
  so fake timestamps **must be epoch millis**. Anything else makes the broadcaster
  either flush every batch (~50 fps to the browser) or never flush.
- `MotorSafetyController.java:687` verifies stops against the **measured** motor
  speed with two consecutive readings inside 5 rpm. A fake drive that returns its
  own setpoint marks every stop instantly "verified" and leaves the coast-down and
  the escalation ladder unexercised.

## One plant model, both fakes read it

The load-cell fake and the drive fake cannot be independent. `CyclicTest`
closes a loop through the hardware: force crosses the upper limit →
`RELEASE_SIGNAL` → `driveRelease()` flips direction → force must actually *fall*
→ `PULL_SIGNAL` → cycle count decrements (`CyclicTest.java:65` reads direction
back off the drive). An independent force generator either never crosses the
thresholds or crosses them regardless of the motor; both leave the cycle logic
untested. `TestContext.java:45` also dedupes on `lastSendSignal`, so the force
must genuinely traverse *between* the limits, not oscillate near one.

So: a `SimulatedBench` singleton both fakes share, ticking every ~5 ms.

| State | Update rule |
|---|---|
| `measuredRpm` | ramps toward the setpoint only while `enabled && started`; on de-energize **coasts down**, never snaps to 0 |
| `position` | `+= measuredRpm · direction · mmPerRev · dt / 60`, clamped at 0 (unloaded specimen) so a long release cannot bank negative travel |
| `force` | the mounted specimen's measured curve at `position` ([specimen-profiles.md](specimen-profiles.md)), then Gaussian sensor noise — **added after the zero clamp**, or an unloaded specimen emits a bit-identical 0.0 every tick and trips the frozen detector |
| sample model | `FRACTURE` (collapse at the specimen's drawn break force) or `UNBREAKABLE` (same curve, no break, for cyclic runs that must not end on their own) |
| link watchdog | once armed (`enableLinkLossStop`, rounded up to 100 ms like P0314), no request from a live handle for the timeout applies the communication-error action — 2 and 5 drop `enabled`, 1 drops `started`. Drive-side: shared by every handle, kept across runs. A faulted handle feeds nothing, so `DRIVE_UNRESPONSIVE` starves it, and so does a transaction `DRIVE_TRANSACTION_WEDGED` holds |
| drive bus | every drive call is one transaction on its handle's I/O thread and takes `deck.simulated.transaction-latency-millis` (3 ms) before it reaches the plant. The handle is device-api's `GatedDrive`, so one transaction is in flight per handle and calls are dispatched by lane, a stop first, as a 1.5.0 driver does ([`device-api.md`](device-api.md#the-call-gate-150)) |

`SimulatedLoadCellStream` emits the deck's own `Measurement(force, currentTimeMillis())`
records ([`device-api.md`](device-api.md)), so the simulated
path needs no vendor jar at all. `dt` is measured from `nanoTime`, not assumed from the
tick interval, so a descheduled tick does not silently change the force curve.
The drive fake's coast-down makes the `EXISTING_HANDLE` → `FRESH_HANDLE` → operator
ladder reachable, and is tuned to reach standstill inside `MotorSafetyController`'s 5 s
verify deadline, so a normal cleanup verifies at tier 1.

Parameters live under `deck.simulated.*`
(`command-deck/src/main/java/ch/rupfizupfi/deck/device/simulated/SimulatedBenchProperties.java`),
with each run's specimen picked from its sample's materials and test parameters, out of profiles
measured off real runs ([specimen-profiles.md](specimen-profiles.md#which-profile-a-run-gets)).

Besides the per-run mount, the specimen is remade — a fresh draw at its pretension — on two
triggers: the drive's energize edge, and opening a load-cell session. The second is
needed because a failed stop never clears the drive's control bits, so the next energize
is not an edge; without it every run after a fracture reads zero force and its cyclic
loop hangs. `SimulatedBench#mountNewSpecimen` documents the one case both miss.

## Seams to extract

**Done.** The `Drive` / `LoadCellStream` seam and the runtime plugin loading that make
both driver jars optional are owned by [`device-api.md`](device-api.md).
A simulated device implements `DriveProvider` / `LoadCellStreamProvider` and needs no
vendor jar.

The relay never needed a jar (it is jSerialComm), but has the same shape: a `RelaySwitch`
interface, `SerialRelaySwitchProvider` / `SimulatedRelaySwitchProvider` gated by
`deck.hardware.mode`, and `SuckService` as the single owner that the dashboard toggle and
`SuckJob` both go through.

## Fault injection — the reason to build this

**Shipped.** Every `MeasurementLoop` detector but the sample-interval bound, and all three
safe-stop tiers, can be tripped on demand through switches that exist only in simulated mode; no
switch produces a trickle, so that bound is covered by unit tests only
([`safety-and-recovery.md`](safety-and-recovery.md#detection-in-measurementloop-woken-by-each-batch)). Switches, the endpoint that arms
them and each observed outcome: [`fault-injection.md`](fault-injection.md).

## What the mode separation does not cover

The database is separated by deployment; the result *files* are not:

- `CSVStoreService` and `TestLogger` both resolve
  `<resultData>/<testResultId>/<millis>_{force.csv,test.log}`.
- `resultData` comes from the JSON settings file, not the DB, and defaults to
  `~/breaktester` (`~` = `user.home`) everywhere (`SettingRepository#defaultSettings`). Only
  the settings file's own location differs: `user.dir` on `dev`, `~/breaktester` otherwise.
- The path is keyed on `testResultId` alone, and ids from different databases are
  unrelated, so a simulated run could drop a force trace into the tree real runs use, under
  an id that means something else there.

**Closed:** `SimulatedStorageLocationService` (a `@Primary` override registered only in
simulated mode) redirects everything to `<resultData>/simulated/`. It overrides the one
accessor both writers share, so cms is untouched; in real mode the bean does not exist.

Permanently out of reach: Modbus framing, CRC and inter-frame timing bugs. `TimeCyclicTest`
*measures* control-loop latency, so its analyse phase is only as meaningful as the latency
the bench models — a constant per transaction, no jitter, no timeouts — the weakest of the
three test types to simulate.

Not built: record/replay of real bench sessions (the open half of OQ-62).

## Where to look in the code

| Concern | File |
|---|---|
| Refcounted lifecycle | `command-deck/src/main/java/ch/rupfizupfi/deck/device/Device.java:30` |
| Where the providers are injected | `command-deck/src/main/java/ch/rupfizupfi/deck/device/DeviceService.java:25` |
| Interfaces a simulated device implements | `command-deck/src/main/java/ch/rupfizupfi/deck/device/api/` |
| The plant model and both fakes | `command-deck/src/main/java/ch/rupfizupfi/deck/device/simulated/` |
| Fault switches + the endpoint that arms them | `command-deck/src/main/java/ch/rupfizupfi/deck/device/simulated/SimulatedFault.java`, `command-deck/src/main/java/ch/rupfizupfi/deck/api/services/SimulatedFaultService.java` |
| Mode enforcement, incl. the production refusal | `command-deck/src/main/java/ch/rupfizupfi/deck/device/HardwareModeCheck.java` |
| Result-root redirect | `command-deck/src/main/java/ch/rupfizupfi/deck/filesystem/SimulatedStorageLocationService.java` |
| Per-run annunciation | `command-deck/src/main/java/ch/rupfizupfi/deck/testrunner/TestRunnerThread.java` |
| Load-cell stream seam | `command-deck/src/main/java/ch/rupfizupfi/deck/device/loadcell/LoadCellDevice.java:46` |
| Drive seam | `command-deck/src/main/java/ch/rupfizupfi/deck/device/frequencyinverter/FrequencyInverterDevice.java:45` |
| Stop verification the fake must satisfy | `command-deck/src/main/java/ch/rupfizupfi/deck/testrunner/MotorSafetyController.java:687` |
| Watchdog constants the fake must respect | `command-deck/src/main/java/ch/rupfizupfi/deck/testrunner/measure/MeasurementLoop.java` |
| Closed loop the bench must close | `command-deck/src/main/java/ch/rupfizupfi/deck/testrunner/program/CyclicTurns.java` |
| Result-file paths | `cms/src/main/java/ch/rupfizupfi/deck/filesystem/CSVStoreService.java:28` |

## Known limits

- A simulated device returns a *distinguishable* identity, so `FrequencyInverterCheck` and the
  simulator are designed together (OQ-44). No vendor code loads in dev, so the simulated provider
  declares its own identity; the bundled `VirtualDeviceConnection` (`WEG` / `VDW-00`) is not used
  ([`driver-jars.md`](driver-jars.md)).
- Relay port matching: the port description is configuration (`device.relay.port-description`),
  but the match is a descriptive-name substring, so no virtual COM port helps even for the one
  device that is a real COM port.
- The specimen is measured, but only as behaviour classes for most runs, and every run is assumed
  to have used speed 350; the drive's ramp and coast rates are invented. Gaps listed in
  [specimen-profiles.md](specimen-profiles.md#what-the-data-cannot-settle). A simulated curve is a
  typical run of its profile — don't use it to judge a real result.
