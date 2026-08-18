> Branch: `dev-split` — implemented 2026-08-18. **Steps 1–4 have shipped.** Only the
> relay fake and record/replay (step 5) are outstanding.

# Design: virtual devices — running a test with no hardware attached

Enables a full `destructive` / `cyclic` run on a developer machine with no load
cell, drive or relay, and — the actual payoff — makes the safety paths *trippable
on demand*. Only tier 1 of `MotorSafetyController` is routinely exercised, by the
`safeStop("test cleanup")` every run ends with; the tier 2 USB re-enumeration,
the tier 3 operator escalation and all three `LoadCellThread` detectors fire only
during an incident, and so have never been observed to work.

Device internals are owned by
[`../../03-backend/hardware-integration.md`](../../03-backend/hardware-integration.md);
watchdog semantics by
[`../testrunner-safety/loadcell-recovery-design.md`](../testrunner-safety/loadcell-recovery-design.md);
the fail-safe-mode and bypass-annunciation reasoning by
[`../../_research/machine-safety.md`](../../_research/machine-safety.md).
Transport findings (why no virtual COM port helps, why the vendor types cannot be
mocked) are in [`../../_research/hardware-simulation.md`](../../_research/hardware-simulation.md).

## Contents

- [Mode contract](#mode-contract)
- [Why the obvious fake cannot work](#why-the-obvious-fake-cannot-work)
- [One plant model, both fakes read it](#one-plant-model-both-fakes-read-it)
- [Seams to extract](#seams-to-extract)
- [Fault injection — the reason to build this](#fault-injection--the-reason-to-build-this)
- [What the mode separation does not cover](#what-the-mode-separation-does-not-cover)
- [Order of work](#order-of-work)
- [Where to look in the code](#where-to-look-in-the-code)
- [Open questions](#open-questions)

## Mode contract

Decided: simulation is a **dev-profile-only** facility and never reaches
production, so no per-run "simulated" marker is needed in the database — a
simulated run's `TestResult` row only ever exists in the dev H2 file
(`command-deck/src/main/resources/application-dev.properties`), never in the
Compose Postgres. See the [caveat below](#what-the-mode-separation-does-not-cover):
this argument holds for DB rows and **not** for the result files.

`deck.hardware.mode` is enforced at startup by `HardwareModeCheck`
([`driver-api-extraction.md`](driver-api-extraction.md#startup-contract)):

| Profile | Hardware | Enforcement |
|---|---|---|
| `dev` | simulated **by default** | `deck.hardware.mode=real` opts back into hardware |
| `docker` | real only | startup **fails** if `deck.hardware.mode=simulated` is set — never silently ignored, and refused before the datasource is touched |
| any other | `real` (the base default) | the property is legal on any profile; only `docker` refuses `simulated` |

Two rules on top, per `machine-safety.md`:

- **No fallback in either direction.** Absent hardware must never select the
  simulator — that makes "unplugged" indistinguishable from "working". A
  requested simulator must never fall back to hardware.
- **Annunciation that cannot be silenced**: a WARN line naming the mode at every
  test start — to the server log *and* the operator's test log, so the run's own
  durable record carries it — plus a persistent UI banner while simulated. The
  banner reads its own `HardwareModeService` and shares no state with
  `StatusService` / `useLiveStatus`, so a realtime fault cannot silence it.

The `real` override on `dev` extends "simulated only on dev" rather than weakening
it: `./gradlew :command-deck:bootRun` is the documented way to run the app and the
bench machine is the only host with hardware, so without the override hardware
could never be driven interactively. Production is unaffected — it refuses
`simulated` outright.

Side effect worth having: `command-deck/src/main/resources/application.properties:1`
sets `spring.profiles.default=dev`, so once `dev` implies simulated, an unset
`SPRING_PROFILES_ACTIVE` resolves to the *safe* state. It still resolves to a
profile that talks to real hardware — the hazard `machine-safety.md` records.

## Why the obvious fake cannot work

A fake returning a counter, a constant, or `System.currentTimeMillis()` as force
does not merely produce poor data — it is **killed by the existing watchdogs
before the first threshold is reached**, and every "fix" for that is a loosened
safety constant. Avoiding that trap is why the plant model below exists.

| Detector | Constant | What a naive fake does |
|---|---|---|
| Plausibility | `command-deck/src/main/java/ch/rupfizupfi/deck/testrunner/LoadCellThread.java:38` rejects \|F\| > 450 kN | a ms timestamp is ~1.7 × 10¹² N → 3-of-5 vote → `sensorLost` → `safeStop`, run over |
| Frozen value | `LoadCellThread.java:35`, 100 bit-identical samples | a *constant* force trips it; a live strain gauge always dithers, so the fake must add LSB noise |
| Implausible rise | `LoadCellThread.java:45`, gain > 150 kN between samples | a clock-derived ramp trips it |
| No-data timeout | `LoadCellThread.java:33`, 250 ms | the fake must keep delivering, not just answer once |

Two silent-corruption traps beyond the watchdogs:

- `ForceBroadcaster.java:20` compares wall clock against `Measurement.timestamp()`,
  so fake timestamps **must be epoch millis**. Anything else makes the broadcaster
  either flush every batch (~50 fps to the browser) or never flush.
- `MotorSafetyController.java:412` verifies stops against the **measured** motor
  speed with two consecutive readings inside 5 rpm. A fake drive that returns its
  own setpoint marks every stop instantly "verified" and leaves the coast-down and
  the whole escalation ladder unexercised — worse than having no simulator.

## One plant model, both fakes read it

The load-cell fake and the drive fake **cannot be independent**. `CyclicTest`
closes a loop through the hardware: force crosses the upper limit →
`RELEASE_SIGNAL` → `cfw11Release()` flips direction → force must actually *fall*
→ `PULL_SIGNAL` → cycle count decrements (`CyclicTest.java:65` reads direction
back off the drive). An independent force generator either never crosses the
thresholds or crosses them regardless of the motor; both leave the cycle logic
untested. `TestContext.java:45` also dedupes on `lastSendSignal`, so the force
must genuinely traverse *between* the limits, not oscillate near one.

So: a `SimulatedBench` singleton both fakes share, ticking every ~5 ms.

| State | Update rule |
|---|---|
| `measuredRpm` | ramps toward the setpoint only while `enabled && started`; on de-energize **coasts down**, never snaps to 0 |
| `position` | `+= measuredRpm · direction · mmPerRev · dt / 60`, clamped at 0 so a long release cannot bank negative travel |
| `force` | `stiffness · max(0, position − slack)`, then LSB dither — **added after the zero clamp**, or an unloaded specimen emits a bit-identical 0.0 every tick and trips the frozen detector |
| sample model | *elastic* or *elastic → yield → fracture*; the default is the latter with a break force above any cyclic limit, so one model serves both test types |

`SimulatedLoadCellStream` emits the deck's own `Measurement(force, currentTimeMillis())`
records ([`driver-api-extraction.md`](driver-api-extraction.md)), so the simulated
path needs no vendor jar at all. `dt` is measured from `nanoTime`, not assumed from the
tick interval, so a descheduled tick does not silently change the force curve.
The coast-down is the point of the drive fake: it is what makes the
`EXISTING_HANDLE` → `FRESH_HANDLE` → operator ladder reachable at all — and it is tuned
to still reach standstill inside `MotorSafetyController`'s 5 s verify deadline, so a
normal cleanup verifies at tier 1 instead of escalating every run.

Parameters live under `deck.simulated.*`
(`command-deck/src/main/java/ch/rupfizupfi/deck/device/simulated/SimulatedBenchProperties.java`),
with optional per-material presets selected by `deck.simulated.material`.

The specimen is discarded and remade between runs — travel back to zero, fracture
healed — on **two** independent triggers, because neither alone suffices: the drive's
energize edge, and opening a load cell session. A stop that *failed* never cleared the
drive's control bits, so the next run's energize is not an edge at all; without the
second trigger every run after a fracture reads zero force forever and its cyclic loop
hangs. See `SimulatedBench#mountNewSpecimen` for the one case both triggers miss.

## Seams to extract

**Done.** The `Drive` / `LoadCellStream` seam, the API it exposes and the optional
source set that makes both driver jars droppable are owned by
[`driver-api-extraction.md`](driver-api-extraction.md) — including why interfaces
alone would *not* fix the fresh-clone build. A simulated device implements
`DriveProvider` / `LoadCellStreamProvider` and needs no vendor jar; nothing else
has to be extracted first.

The relay is the one device out of scope there, because it never needed a jar:
`FourWayRelaySwitch` is already subclassable (`getComPort()` is protected), so the
fake subclasses it. Its two `new` sites — `SuckService.java:20`, `SuckJob.java:20`
— are jSerialComm, not vendor-jar, and were never part of the extraction.
(`_research/hardware-simulation.md` says seven `new` sites; grep finds five: three
vendor, now behind providers, plus these two.)

## Fault injection — the reason to build this

**Shipped.** Every `LoadCellThread` detector and all three safe-stop tiers can now be
tripped on demand, through switches that only exist in simulated mode. The switch list,
the endpoint that arms them and the observed outcome of each:
[`fault-injection.md`](fault-injection.md).

## What the mode separation does not cover

**Verified gap, and the one place the "no flag needed" argument stops holding.**
The DB is profile-separated; the result *files* are not:

- `CSVStoreService.java:28` and `TestLogger.java:27` both resolve
  `<resultData>/<testResultId>/<millis>_{force.csv,test.log}`.
- `resultData` comes from a JSON settings file, not the DB, and defaults to
  `~/breaktester` in **both** profiles (`SettingRepository.java:30`). Only the
  settings *file location* is profile-dependent (`SettingRepository.java:69`:
  `user.dir` on dev, `~/breaktester` otherwise).
- The path is keyed on `testResultId` alone, and dev H2 ids are unrelated to
  Postgres ids. A simulated dev run can therefore drop a force trace into the
  same tree real runs use, under an id that means something else in the other
  database.

Since test *outcome* is never persisted anyway (audit finding H10), the CSV is
the durable artefact of a run — so this is the file that could be mistaken for
real material data. **Closed:** `SimulatedStorageLocationService` (a `@Primary`
override registered only in simulated mode) redirects everything to
`<resultData>/simulated/`. Overriding the one accessor both writers share keeps
cms untouched; in real mode the bean does not exist.

Also outside reach, permanently: Modbus framing, CRC and inter-frame timing bugs
— see `_research/hardware-simulation.md`. And `TimeCyclicTest.java:141` *measures*
control-loop latency, so its analyse phase is only as meaningful as the latency
the bench models; it is the weakest of the three test types to simulate.

## Order of work

| # | Step | Note |
|---|---|---|
| 1 | ~~Extract the API, route the vendor `new` sites through providers, move the adapters to an optional source set~~ — [`driver-api-extraction.md`](driver-api-extraction.md) | **done**; `deck.hardware.mode` landed with it |
| 2 | ~~`SimulatedBench` + the two simulated implementations, then flip the dev profile to `simulated`~~ | **done** |
| 3 | ~~Result-data root override, WARN-per-run, UI banner~~ | **done**, and it landed with step 2 rather than after it |
| 4 | ~~Fault-injection switches, toggled from a dev-only endpoint~~ | **done** — the payoff |
| 5 | Relay fake; record/replay of real sessions | lowest value — the relay is one fire-and-forget ASCII byte |

## Where to look in the code

| Concern | File |
|---|---|
| Refcounted lifecycle (unchanged by this design) | `command-deck/src/main/java/ch/rupfizupfi/deck/device/Device.java:17` |
| Where the providers are injected | `command-deck/src/main/java/ch/rupfizupfi/deck/device/DeviceService.java:25` |
| Interfaces a simulated device implements | `command-deck/src/main/java/ch/rupfizupfi/deck/device/api/` |
| The plant model and both fakes | `command-deck/src/main/java/ch/rupfizupfi/deck/device/simulated/` |
| Fault switches + the endpoint that arms them | `command-deck/src/main/java/ch/rupfizupfi/deck/device/simulated/SimulatedFault.java`, `command-deck/src/main/java/ch/rupfizupfi/deck/api/services/SimulatedFaultService.java` |
| Mode enforcement, incl. the production refusal | `command-deck/src/main/java/ch/rupfizupfi/deck/device/HardwareModeCheck.java` |
| Result-root redirect | `command-deck/src/main/java/ch/rupfizupfi/deck/filesystem/SimulatedStorageLocationService.java` |
| Per-run annunciation | `command-deck/src/main/java/ch/rupfizupfi/deck/testrunner/TestRunnerThread.java` |
| Load-cell stream seam | `command-deck/src/main/java/ch/rupfizupfi/deck/device/loadcell/LoadCellDevice.java:46` |
| Drive seam + drive lock | `command-deck/src/main/java/ch/rupfizupfi/deck/device/frequencyconverter/CFW11Device.java:63` |
| Stop verification the fake must satisfy | `command-deck/src/main/java/ch/rupfizupfi/deck/testrunner/MotorSafetyController.java:400` |
| Watchdog constants the fake must respect | `command-deck/src/main/java/ch/rupfizupfi/deck/testrunner/LoadCellThread.java:37` |
| Closed loop the bench must close | `command-deck/src/main/java/ch/rupfizupfi/deck/testrunner/CyclicTest.java:59` |
| Result-file paths | `cms/src/main/java/ch/rupfizupfi/deck/filesystem/CSVStoreService.java:28` |

## Open questions

Touching existing items — none of them is resolved by this design:

1. **OQ-44** (`Cfw11Check`): a simulated device must return a *distinguishable*
   identity, so the check and the simulator have to be designed together. With the
   drivers optional no vendor code loads in dev, so the simulated provider declares
   its own identity — the bundled `VirtualDeviceConnection` (`WEG` / `VDW-00`) is
   **not** used ([`driver-jars.md`](../../03-backend/driver-jars.md)).
2. **OQ-46** (relay `CH9102` literal): the substring match is why no virtual COM
   port would help even for the one device that is a real COM port.
3. **OQ-50** (two drive handles on one device): fault injection is how this
   becomes answerable without risking the rig.
4. **OQ-43** (`usbmodbus.jar` provenance): the build half is closed; procurement
   is still owner-owed.

Not filed as `OQ-n`:

- Sample-model parameters (stiffness, slack, break force, `mmPerRev`) and the
  per-material presets are pure invention until someone reads them off a real
  run's force trace. Simulated force curves are **shape-plausible, not
  calibrated** — they must not be used to judge whether a real result looks right.
  `doc/resource/sample.csv` and `doc/resource/material.csv` do **not** close this:
  they are `Sample` / `Material` entity seed rows (gear inventory), not force
  traces, so they supply names to hang presets on and no measurements at all.
