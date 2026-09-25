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
| Provides | `ch.rupfizupfi.dscusb.dscusb.CellValueStream`, itself a `LoadCellStream`; `CommandExecutionException` one level up | `ch.rupfizupfi.usbmodbus.Cfw11` |
| Deck plugin package | `ch.rupfizupfi.dscusb.deck` — `DeckLoadCellAutoConfiguration` | `ch.rupfizupfi.usbmodbus.deck` — `Cfw11Drive`, `DeckDriveAutoConfiguration` |
| In git | no — `/drivers/*.jar` is gitignored | no — gitignored, licence-restricted |
| Published | as `ch.rupfizupfi.dscusb:dscusb` on GitHub Packages — `0.2.0` is up, `0.3.0` publishes when the driver branch merges | **never** — may not be redistributed |
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
([`dscusb-serial-port`](../06-feature-work/dscusb-serial-port/README.md)). That leaves
the `docker` deck profile for tests and simulations at `deck.hardware.mode=simulated`,
with no driver in the image ([`docker-and-profiles.md`](../05-ops/docker-and-profiles.md)).

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

A missing jar is therefore a *startup* failure, never a compile failure, and the fix
is a restart
([gradle-build.md](../02-modules/gradle-build.md#driver-plugins-loaderpath-not-the-classpath)).
On a dev or bench machine one directory serves every path — `drivers/`, read by
`bootRun`, `run-bench.ps1` and `driverPluginTest` alike
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
simulators. It touches no hardware. Run it after copying a jar in — from `0.3.0`
both manifests carry `Implementation-Version`, the only way to date a jar by hand.

## `dscusb.jar` — load cell

Published from the sibling repo as `ch.rupfizupfi.dscusb:dscusb` — by its CI on
merge to `main` whenever `version` in its `gradle.properties` changes, or by hand
with `./gradlew publish` there (needs `GITHUB_ACTOR` and a `write:packages` token).
`0.2.0` is published; `0.3.0` — everything marked below — sits on the sibling's
`feat/deck-plugin` and publishes on merge. **Nothing here resolves it any more:** the
deck image carries no driver, being the simulation deployment, and the bench copies
jars in by hand — so `stageDrivers`, its version pin and the GitHub Packages
repository it needed are gone. Publishing now serves consumers with no sibling
checkout: a second machine, or the driver's own CI.

Publishing is the delivery path, **not** the conformance check — that is still the
driver repo's compile against the live contract, so a version published from a stale
checkout compiles against a stale one with nothing downstream to catch it. For bench
work there is no need to publish at all: `./gradlew shadowJar` in the sibling repo and
copy `build/libs/dscusb.jar` into `drivers/`. No launch path resolves `mavenLocal`.

**The package layout is split.** `CellValueStream`, `Connection`, `LoadCellSource`
(its seam onto the DLL, so the read loop is testable without it), `DSCUSB` and
`DSCUSBDrv64` sit in `ch.rupfizupfi.dscusb.dscusb`, beside a `t24` package for the
wireless base station the deck does not use; `CommandExecutionException` sits one
level up, shared. Since `0.3.0` `CellValueStream` implements `LoadCellStream` itself
— no adapter — and both backends carry the deck's `Measurement`, making `device-api`
a `compileOnly` dependency of the whole repo, not just its `…dscusb.deck` package.

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
  unknown code — stopping with a named cause beats retrying a condition the table
  cannot reason about. `-100` is terminal despite reading as transient: the vendor
  docs say the DLL *"will halt all processing while waiting for a response from
  the instrument"* with a default timeout of 300 ms, so a `-100` has already
  punched a hole longer than the deck's watchdog when thrown. Retrying cannot
  save a run already over, and dropping it would leave `isReading()` true —
  bare silence with no named cause. The vendor documents only `0/-1/-100/-200/-400`
  for `READCOMMAND`; the rest come from the driver header, so classifying those is
  judgement about meaning, not observation.
- `LoadCellStream.droppedSampleCount()` is the **only** trace a recovered burst
  leaves: the stream reads on, `lastError()` stays null, and the timestamps show
  a hole without saying whether the driver rejected readings or the bus was just
  slow. `LoadCellDevice.readData` logs it on change, `LoadCellThread`'s incident
  line carries it, and nothing bounds a run's *total* dropped fraction — OQ-81.
- **`stopReading()` returns only once the reader has released the process-global
  DLL port**, which is what `Device.reset()`'s close-then-reopen relies on. A
  reader abandoned past the join bound never closes it — the next open owns it.
- **A stopped stream can never be restarted.** Reconnection must construct a new
  `CellValueStream`; this is why `LoadCellStreamProvider` is a factory, and the
  constraint the
  [recovery design](../06-feature-work/testrunner-safety/loadcell-recovery-design.md)
  is built around.
- `isReading()` / `lastError()` expose why the reader stopped. The stream
  flattens its own throwable into a `StreamFailure`, and
  `LoadCellDevice#getStreamFailure` turns that into a named cause for the trip
  reason — **diagnosis only**: what escalates is always the silence, so a sensor
  that dies without explanation trips identically.

## `usbmodbus.jar` — frequency inverter

Never committed, never published, never baked into an image — the licence does not permit redistribution.
It reaches exactly one place: build it in the sibling repo and copy `build/libs/usbmodbus.jar` into
`drivers/` on the bench machine. No image carries it and no host mount delivers it to a container.
A fresh clone builds and a fresh image builds without it; neither can drive the machine.
Vendor, licence holder and required version are recorded nowhere (OQ-43); only the project owner can close that.

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
- `close()` releases it, and only if that instance opened it. The second constructor takes a
  caller-owned `ModbusUsbHelper` and closes nothing — the seam for a virtual Modbus slave or a test double.
- **Both getter maps have required key sets**: `getMotorData` must carry `speed`, `current`,
  `voltage`, `torque`; `getControlParameters` `start`, `generalEnable`, `useSecondRamp`,
  `directionIsForward`. A missing key is a poll failure naming it, never a zero reading.
- **Every comms failure arrives checked**, as one of three types: `NegativeConfirmationException`
  (`"Send Not OK"`, `"Read Not OK"`, `"Timeout"`, `"Frame error"`), `ModbusExceptionResponseException`
  (the slave answered with an exception PDU) and `ModbusUnexpectedResponseException` (wrong function
  code or byte count). Reads and writes share one path, so a timed-out *write* throws too. Retries
  exist but are off (`maximumRetries` defaults to 0), leaving one attempt at a 100 ms timeout.
- **None of the three is a `RuntimeException`** and Kotlin lets them cross into Java undeclared, so
  both poll loops — `FrequencyInverterDevice#readData` and `LoadCellDevice#readData` — catch `Exception`:
  a driver failure idles that tick and the poll thread survives, the drive logging once per outage.
  `Cfw11Drive` wraps all three unchecked in `Cfw11CommunicationException`, so every other call site
  sees a `RuntimeException`. Safety paths catch `Throwable` (`MotorSafetyController#verifyStopped`,
  `commandStop`); traced in the `usbmodbus` repo's comms-failure-handling doc.
- **A value too wide for its 16-bit register is refused**, unchecked (`Cfw11RangeException`), before
  any register is written — a speed reference outside the signed range included, so 4000 rpm is a
  refusal rather than full-scale reverse.
- **A frame carrying fewer registers than asked for is refused**, unchecked
  (`Cfw11ProtocolException`), never yielding a reading — every read shares one path.

`CommunicationLib.jar` is also where `devicemanager.VirtualDeviceConnection`
lives — an in-memory Modbus slave reporting vendor `WEG` / product `VDW-00`.
The deck does **not** use it: with the drivers optional, no vendor code loads in
dev at all, so the simulated provider declares its own identity for `FrequencyInverterCheck`
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
