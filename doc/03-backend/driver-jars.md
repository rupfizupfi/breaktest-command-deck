> Branch: `feat/driver-plugins` — captured 2026-08-27.

# The two driver plugin JARs

## Purpose

`dscusb.jar` and `usbmodbus.jar` are built from sibling repos outside this one,
so nothing here tracks their source. This page owns where each comes from, how
it reaches a running deck, and the parts of its behaviour that decide run
outcomes. The build wiring is
[`gradle-build.md`](../02-modules/gradle-build.md); how the wrappers are used is
[`hardware-integration.md`](hardware-integration.md).

**Both are absent from every build and mandatory to run.** Nothing in this repo
imports either: each jar is a self-contained deck plugin that implements
`ch.rupfizupfi.deck:device-api` (the `device-api/` composite build here) and
registers its provider beans through its own Spring Boot auto-configuration. The
boot jar contains no driver at all — `PropertiesLauncher` loads them at launch
from the `loader.path` directories, so a driver is swapped by replacing a file
and restarting. `deck.hardware.mode=real` refuses to start without both provider
beans — see
[`driver-api-extraction.md`](../06-feature-work/virtual-devices/driver-api-extraction.md).

## Contents

- [At a glance](#at-a-glance)
- [Both drivers are Windows-only, and that decides the deployment](#both-drivers-are-windows-only-and-that-decides-the-deployment)
- [The jar on the machine is checked at startup](#the-jar-on-the-machine-is-checked-at-startup)
- [`dscusb.jar` — load cell](#dscusbjar--load-cell)
- [`usbmodbus.jar` — frequency inverter](#usbmodbusjar--frequency-inverter)
- [Open questions](#open-questions)

## At a glance

| | `dscusb.jar` | `usbmodbus.jar` |
|---|---|---|
| Provides | `ch.rupfizupfi.dscusb.dscusb.CellValueStream`; `Measurement` and `CommandExecutionException` one level up | `ch.rupfizupfi.usbmodbus.Cfw11` |
| Deck plugin package | `ch.rupfizupfi.dscusb.deck` — `CellValueStreamAdapter`, `DeckLoadCellAutoConfiguration` | `ch.rupfizupfi.usbmodbus.deck` — `Cfw11Drive`, `DeckDriveAutoConfiguration` |
| In git | no — `/drivers/*.jar` is gitignored | no — gitignored, licence-restricted |
| Published | as `ch.rupfizupfi.dscusb:dscusb` on GitHub Packages — the mechanism is in place, the **first version lands when the driver PR merges** | **never** — may not be redistributed |
| Reaches the bench via | copy into `drivers/` → `LOADER_PATH` | copy into `drivers/` → `LOADER_PATH` |
| Sibling repo | `dscusb` | `usbmodbus` |
| Buildable on this machine | yes, from a clean checkout | yes, from a clean checkout |
| Reaches hardware via | jnr-ffi → `DSCUSBDrv64.dll`, by serial number | Thesycon `usbiojava_x64.dll` → USBIO kernel driver |
| **Runs on** | **Windows x64 only** | **Windows x64 only** |

## Both drivers are Windows-only, and that decides the deployment

Neither native library has a Linux build, and neither is *in* either repo — both
come from a machine-wide vendor install on the bench PC. `DSCUSBDrv64.dll` is a
Win32 library on FTDI's Windows D2XX stack; `usbiojava_x64.dll` is a JNI shim
over the Thesycon USBIO **kernel-mode** driver that finds devices by Windows
device-interface GUID. So **the `docker` profile's Linux image cannot drive the
bench.** That is settled rather than open: the bench controller stays a Windows PC
and the deck runs natively on it
([`bench-deployment.md`](../05-ops/bench-deployment.md)). Rewriting the drivers onto
serial to make a Linux deck possible is recorded as a future option only
([`dscusb-serial-port`](../06-feature-work/dscusb-serial-port/README.md)). The `docker`
deck profile is not unusable but differently employed: **tests and simulations**, at
`deck.hardware.mode=simulated`, with no driver in the image
([`docker-and-profiles.md`](../05-ops/docker-and-profiles.md)).

Each driver's auto-configuration therefore refuses to register off Windows and
logs why. Registering and failing later would let `HardwareModeCheck` pass and put
the `UnsatisfiedLinkError` in the middle of a run; instead the deck refuses at
startup and names the jar, with the driver's own warning just above it explaining
that the jar is present and the *platform* is wrong.

Both repos build on Gradle 9.7 / Kotlin 2.4.10 / gradleup shadow / JVM target 26,
are reproducible from their committed source, and
`includeBuild("../breaktest-command-deck/device-api")` to implement the deck's
contract in a `deck` package (`compileOnly` on the contract and on
`spring-boot-autoconfigure`, so neither is bundled — contract classes inside a
driver jar would shadow the deck's own). **Their compile against the live contract
is the conformance guarantee**: a `device.api` change surfaces as a compile error
on the next driver build. Without that sibling checkout each falls back to the
published `ch.rupfizupfi.deck:device-api`, which pins a version rather than
tracking the contract, so a build there proves nothing about drift.

The deck loads them at launch from the `loader.path` directories
([gradle-build.md](../02-modules/gradle-build.md#driver-plugins-loaderpath-not-the-classpath)),
so a missing jar is a *startup* failure, never a compile failure, and the fix is a
restart. On a dev or bench machine one directory serves every path — `drivers/`,
read by `bootRun`, `run-bench.ps1` and `driverPluginTest` alike
([`drivers/README.md`](../../drivers/README.md)).

## The jar on the machine is checked at startup

Because a driver is a file drop, the jar running is whatever someone last copied
there — no compile of either repo can see it. Both auto-configurations therefore
call `DeviceApi.verifyPluginBuiltAgainst(ContractVersion.VALUE)` in their
initializer and **refuse startup** on an incompatible contract version, naming
both versions. Mechanism and the compatibility rules:
[`driver-api-extraction.md`](../06-feature-work/virtual-devices/driver-api-extraction.md#conformance-guarantee).
The fix is a driver rebuild and a file copy, never an application rebuild.

Living in the plugin, the check cannot catch **a jar that never calls it** — one
built before the check existed loads clean and proves nothing.
`./gradlew :command-deck:driverPluginTest` closes that: it requires the call
before trusting its result, instantiates each auto-configuration so a skewed jar
reports the driver's own message, and with both jars present boots the real
context at `deck.hardware.mode=real` to assert the vendor providers displaced the
simulators. It touches no hardware. Run it after copying a jar in; a stale jar
looks exactly like a current one.

## `dscusb.jar` — load cell

Published from the sibling repo as `ch.rupfizupfi.dscusb:dscusb` — by its CI on
merge to `main` whenever `version` in its `gradle.properties` changes, or by hand
with `./gradlew publish` there (needs `GITHUB_ACTOR` and a `write:packages` token).
`0.2.0` shipped; `0.3.0` follows OQ-74 (below). **Nothing in this repo resolves it
any more:** the deck image carries no driver, being the simulation deployment, and
the bench copies jars into `drivers/` by hand — so `stageDrivers`, its version pin
and the GitHub Packages repository it needed are gone. Publishing now serves
consumers with no sibling checkout: a second machine, or the driver's own CI.

Publishing is the delivery path, **not** the conformance check — that is still the
driver repo's own compile against the live contract, so a version published from a
stale checkout compiles against a stale one with nothing downstream to catch it.

For bench work there is no need to publish: `./gradlew shadowJar` in the sibling
repo and copy `build/libs/dscusb.jar` into `drivers/`. `publishToMavenLocal` is no
longer part of this — `mavenLocal` was dropped once one directory served every
launch path.

**The package layout is split, and only part of it moved.** `CellValueStream`,
`Connection`, `DSCUSB` and `DSCUSBDrv64` sit in `ch.rupfizupfi.dscusb.dscusb`,
beside a `t24` sibling package for the wireless base station the deck does not use.
`Measurement` and `CommandExecutionException` are shared by both backends and stay
one level up in `ch.rupfizupfi.dscusb`. `CellValueStreamAdapter` (in the `dscusb`
repo's own `…dscusb.deck` package) imports from both, which is the whole blast
radius of that move — the deck owns its own `Measurement`.

**We load the optional DLL, not the COM-port one.** Mantracourt ship two:
`MantraASCII2.DLL` over the FTDI virtual COM port, and `DSCUSBDrv.DLL` addressing
modules directly by serial number — the manual's "preferred method", and the one
the driver loads. Its only substantive gain is **up to 127 modules on one bus**;
the bench has one cell, so the COM-port path would cost nothing we use, and it is
what any non-Windows driver would have to speak. The device also has a **continuous output
mode** (`SOUT`, XON/XOFF-gated, ASCII protocol only) that would replace polling
outright — unreachable through this DLL, so unexploited (OQ-80). Protocol, the
`1781:0BAD` Linux enumeration blocker and the rest:
[`dscusb-serial-port`](../06-feature-work/dscusb-serial-port/README.md).

**Driver contract, and it decides run outcomes:**

- `READCOMMAND` signals errors by return code only, so a non-finite float
  alongside a success code is a contract violation. `DSCUSB.readCommand` seeds
  its out-parameter with `NaN` and throws `-800` instead of returning it —
  unchanged by OQ-74.
- **A transient fault is dropped, not fatal** (`dscusb` 0.3.0, OQ-74 answered).
  The reader discards the reading and reads on within an **80 ms wall-clock
  budget** — wall clock rather than a retry count because the loop never sleeps,
  so N retries is an unpredictable amount of missing data and what has to be
  bounded is the length of the hole. 80 ms is sized under the deck's 250 ms
  no-data watchdog so a *recovered* burst cannot trip it. Past the budget the
  original exception is rethrown unwrapped, so the trip reason still names the
  driver's own code. Droppable: `-200`, `-300`, `-500`, `-600`, `-700`, `-800`.
- **Terminal, and still ending the stream:** `-1`, `-2`, `-100`, `-400`, and any
  unknown code — stopping with a named cause beats retrying a condition the
  table cannot reason about. `-100` is terminal despite reading as transient:
  the vendor documentation says the DLL *"will halt all processing while waiting
  for a response from the instrument"* with a default timeout of 300 ms, so a
  `-100` has already punched a hole longer than the deck's watchdog by the time
  it is thrown. Retrying cannot save a run that is already over, and dropping it
  would leave `isReading()` true — bare silence with no named cause. Note the
  vendor only documents `0/-1/-100/-200/-400` for `READCOMMAND`; `-300`, `-500`,
  `-600` and `-700` come from the driver header, so classifying those is a
  judgement about meaning, not an observation.
- `LoadCellStream.droppedSampleCount()` is the **only** trace a recovered burst
  leaves: the stream keeps reading, `lastError()` stays null, and the sample
  timestamps show a hole without saying whether the driver rejected readings or
  the bus was merely slow. `LoadCellDevice.readData` logs it on change and
  `LoadCellThread`'s incident line carries it. Nothing bounds the *total*
  dropped fraction of a run — OQ-81.
- **A stopped stream can never be restarted.** Reconnection must construct a new
  `CellValueStream`; this is why `LoadCellStreamProvider` is a factory, and the
  constraint the
  [recovery design](../06-feature-work/testrunner-safety/loadcell-recovery-design.md)
  is built around.
- `isReading()` / `getLastError()` expose why the reader stopped. The adapter
  flattens the throwable into a `StreamFailure`, and
  `LoadCellDevice#getStreamFailure` turns that into a named cause for the trip
  reason — **diagnosis only**: what escalates is always the silence, so a sensor
  that dies without explanation trips identically.

## `usbmodbus.jar` — frequency inverter

Never committed, never published, never baked into an image — the licence does not
permit redistribution. It reaches exactly one place: build it in the sibling repo
and copy `build/libs/usbmodbus.jar` into `drivers/` on the bench machine. No image
carries it and no host mount delivers it to a container. A fresh clone builds and a
fresh image builds without it; neither can drive the machine.
Vendor, licence holder and required version are recorded nowhere (OQ-43); only
the project owner can close that.

The blocker is narrower than "the jar". It is the **vendor** libraries the
sibling repo's shadow build bundles — `CommunicationLib.jar` and
`ThesyconUSBLib.jar`, both 2018 — not the `ch.rupfizupfi.usbmodbus` code.
Splitting them apart would let the project half be committed, which is worth
raising when OQ-43 is answered.

Those vendor jars are themselves **committed** to the `usbmodbus` remote, which
is **private** (verified 2026-08-27) — so the position holds, but only because
that repo is private. Making it public would redistribute them.

**Driver contract, and it decides run outcomes:**

- `Cfw11` is `final` (Kotlin), so it cannot be subclassed — that is why `Drive` is
  an interface with a delegating adapter rather than a subclass.
- The no-arg constructor **opens the USB device**. There is no unopened instance, so
  a fresh handle means a new object; this is what `DriveProvider` being a factory
  buys, and what tier 2's `stopWithFreshHandle` relies on.
- `close()` releases it, and only if that instance opened it. The second
  constructor takes a caller-owned `ModbusUsbHelper` and closes nothing — the seam
  for a virtual Modbus slave or a test double.
- **Every comms failure arrives as a checked `NegativeConfirmationException`** —
  `"Send Not OK"` (includes USB not connected), `"Read Not OK"`, `"Timeout"`,
  `"Frame error"`. Reads and writes share one path, so a timed-out *write* throws
  too; nothing here is fire-and-forget. Retries exist but are off
  (`maximumRetries` defaults to 0), leaving one attempt at a 100 ms timeout.
- **That exception is not a `RuntimeException`**, and Kotlin lets it cross into Java
  undeclared. `FrequencyInverterDevice#readData` catches only `DriveUnavailableException` and
  `RuntimeException`, so a comms error **escapes the poll loop and kills the
  info-polling thread**, leaving the dashboard on stale values. The safety paths are
  fine — `MotorSafetyController#verifyStopped` and `commandStop` catch `Throwable`.
  Traced and proposed for review in the `usbmodbus` repo's comms-failure-handling doc.
- `Cfw11`'s `catch (NullPointerException)` returning `"0"` looks like it masks a dead
  link as a real zero. It does not: no null is reachable along that chain, so the
  catch never fires. Dead code, not a live defect.

`CommunicationLib.jar` is also where `devicemanager.VirtualDeviceConnection`
lives — an in-memory Modbus slave reporting vendor `WEG` / product `VDW-00`.
The deck does **not** use it: with the drivers optional, no vendor code loads in
dev at all, so the simulated provider declares its own identity for `Cfw11Check`
(OQ-44). The injecting constructor above is what an optional wire-level fidelity
path would need; nothing consumes it yet.

Its `commandbus.CommandChain` is present and deliberately unused: it serialises
writes only, is fire-and-forget, and cannot carry a return value or an exception,
so it cannot back a stop that must know whether the motor stopped. Reasoning in
[`hardware-layer-redesign`](../06-feature-work/hardware-layer-redesign/README.md#what-stays-unchanged-deliberately).

## Open questions

| OQ | Topic |
|---|---|
| OQ-43 | `usbmodbus.jar` provenance — owner-owed |
| OQ-81 | Only *consecutive* driver faults are budgeted — nothing bounds a run's total dropped fraction |
| OQ-80 | Continuous output mode would remove load-cell polling — unexploited, unverified on this variant |
