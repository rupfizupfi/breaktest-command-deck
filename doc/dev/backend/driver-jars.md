# The two driver plugin JARs

## Purpose

`dscusb.jar` and `usbmodbus.jar` are built from sibling repos outside this one, so nothing here
tracks their source. This page owns where each comes from, how it reaches a running deck, and the
parts of its behaviour that decide run outcomes. The build wiring is
[`gradle-build.md`](../modules/gradle-build.md); how the wrappers are used is
[`hardware-integration.md`](hardware-integration.md).

**Both are absent from every build and mandatory to run.** Nothing in this repo imports either: each
jar is a self-contained deck plugin that implements `ch.rupfizupfi.deck:device-api` (the
`device-api/` composite build here) and registers its provider beans through its own Spring Boot
auto-configuration. The boot jar contains no driver at all — `PropertiesLauncher` loads them at
launch from the `loader.path` directories, so a driver is swapped by replacing a file and
restarting. `deck.hardware.mode=real` refuses to start without both provider beans — see
[`device-api.md`](device-api.md).

## Contents

- [At a glance](#at-a-glance)
- [Both drivers are Windows-only, and that decides the deployment](#both-drivers-are-windows-only-and-that-decides-the-deployment)
- [The jar on the machine is checked at startup](#the-jar-on-the-machine-is-checked-at-startup)
- [`dscusb.jar` — load cell](#dscusbjar--load-cell)
- [`usbmodbus.jar` — frequency inverter](#usbmodbusjar--frequency-inverter)
- [Known limits](#known-limits)

## At a glance

| | `dscusb.jar` | `usbmodbus.jar` |
|---|---|---|
| Provides | `ch.rupfizupfi.dscusb.dscusb.CellValueStream`, itself a `LoadCellStream`; `CommandExecutionException` one level up | `ch.rupfizupfi.usbmodbus.Cfw11` |
| Deck plugin package | `ch.rupfizupfi.dscusb.deck` — `DeckLoadCellAutoConfiguration` | `ch.rupfizupfi.usbmodbus.deck` — `Cfw11Drive`, `DeckDriveAutoConfiguration` |
| In git | no — `/drivers/*.jar` is gitignored | no — gitignored, licence-restricted |
| Published | as `ch.rupfizupfi.dscusb:dscusb` on GitHub Packages — `0.2.0` is up, `0.3.0` publishes when the driver branch merges | **never** — may not be redistributed |
| Reaches the bench via | copy into `drivers/` → `LOADER_PATH` | copy into `drivers/` → `LOADER_PATH` |
| Sibling repo | `dscusb` | `usbmodbus` |
| device-api pin, contract test | `1.4.0`, `CellValueStreamContractTest` | `1.4.0`, `Cfw11DriveContractTest` ([contract tests](device-api.md#contract-evolution)) |
| Buildable on this machine | yes, from a clean checkout | yes, from a clean checkout |
| Reaches hardware via | jnr-ffi → `DSCUSBDrv64.dll`, by serial number | Thesycon `usbiojava_x64.dll` → USBIO kernel driver |
| **Runs on** | **Windows x64 only** | **Windows x64 only** |

## Both drivers are Windows-only, and that decides the deployment

Neither native library has a Linux build, and neither is *in* either repo — both come from a
machine-wide vendor install on the bench PC. `DSCUSBDrv64.dll` is a Win32 library on FTDI's Windows
D2XX stack; `usbiojava_x64.dll` is a JNI shim over the Thesycon USBIO **kernel-mode** driver that
finds devices by Windows device-interface GUID. So **the `docker` profile's Linux image cannot drive
the bench.** Decided: the bench controller stays a Windows PC and the deck runs natively on it
([`bench-deployment.md`](../../ops/bench-deployment.md)). A serial rewrite for a Linux deck is a
future option only ([`decisions.md`](../../decisions.md#bench-platform-windows-native-owner-2026-08-30)). The
`docker` deck profile is for tests and simulations at `deck.hardware.mode=simulated`, with no driver
in the image ([`docker-and-profiles.md`](../../ops/docker-and-profiles.md)).

Each driver's auto-configuration refuses to register off Windows and logs why, so
`HardwareModeCheck` fails startup naming the jar instead of an `UnsatisfiedLinkError` surfacing
mid-run.

Both repos build on Gradle 9.7 / Kotlin 2.4.10 / gradleup shadow / JVM target 26, are reproducible
from their committed source, and `includeBuild("../breaktest-command-deck/device-api")` to implement
the deck's contract in a `deck` package (`compileOnly` on the contract and on
`spring-boot-autoconfigure`, so neither is bundled — contract classes inside a driver jar would
shadow the deck's own). **Their compile against the live contract is the conformance guarantee**: a
`device.api` change surfaces as a compile error on the next driver build. Without that sibling
checkout each falls back to the published `ch.rupfizupfi.deck:device-api`, which pins a version, so
a build there proves nothing about drift.

A missing jar is a *startup* failure, never a compile failure; the fix is a restart
([gradle-build.md](../modules/gradle-build.md#driver-plugins-loaderpath-not-the-classpath)). On a
dev or bench machine one directory serves every path — `drivers/`, read by `bootRun`,
`run-bench.ps1` and `driverPluginTest` alike ([`drivers/README.md`](../../../drivers/README.md)).

## The jar on the machine is checked at startup

A driver is a file drop, so no compile sees which jar is running. Both auto-configurations call
`DeviceApi.verifyPluginBuiltAgainst(ContractVersion.VALUE)` in their initializer and **refuse
startup** on an incompatible contract version, naming both versions. Mechanism and the compatibility
rules: [`device-api.md`](device-api.md#conformance-guarantee). The fix is a driver rebuild and a
file copy, never an application rebuild.

Living in the plugin, the check cannot itself catch **a jar that never calls it**; the deck's
`PluginContractCheck` refuses such a provider at startup
([`device-api.md`](device-api.md#conformance-guarantee)). `./gradlew :command-deck:driverPluginTest`
names such a jar before it is deployed: it requires the call before trusting its result,
instantiates each auto-configuration so a skewed jar reports the driver's own message, and with both
jars present boots the real context at `deck.hardware.mode=real` to assert the vendor providers
displaced the simulators. It touches no hardware. Run it after copying a jar in — from `0.3.0` both
manifests carry `Implementation-Version`, the only way to date a jar by hand.

An older minor passes the skew check by design, so a contract `default` whose fallback throws
needs its own catch: a drive jar built before `Drive#enableLinkLossStop` (contract 1.2.0) loads,
then every energize is refused. `driverPluginTest` fails such a jar by name. A drive jar built before
`Drive#gating()` (1.5.0) loads with a startup warning, and since the deck dropped its own drive lock
(BL-121) every energize is refused too: it can still stop and de-energize the drive, never run a test. A safe fallback only
warns, at every startup and in `driverPluginTest`: a jar older than `Drive#readControlSource`
(1.3.0) answers `UNKNOWN`, so every boot de-energizes even a drive under the button board (REMOTE) — the
pre-1.3.0 behaviour — and a load-cell jar older than `LoadCellStream#droppedSampleCount()` (1.1.0)
answers 0, so dropped samples are not reported.

## `dscusb.jar` — load cell

Published from the sibling repo as `ch.rupfizupfi.dscusb:dscusb` — by its CI on merge to `main`
whenever `version` in its `gradle.properties` changes, or by hand with `./gradlew publish` there
(needs `GITHUB_ACTOR` and a `write:packages` token). `0.2.0` is published; `0.3.0` — everything
marked below — sits on the sibling's `feat/deck-plugin` and publishes on merge. Nothing in this repo
resolves it; the package serves consumers with no sibling checkout (a second machine, the driver's
own CI).

Publishing is **not** the conformance check — a version published from a stale checkout compiles
against a stale contract with nothing downstream to catch it. Bench work needs no publish:
`./gradlew shadowJar` in the sibling repo and copy `build/libs/dscusb.jar` into `drivers/`. No
launch path resolves `mavenLocal`.

**The package layout is split.** `CellValueStream`, `Connection`, `LoadCellSource` (its seam onto
the DLL, so the read loop is testable without it), `DSCUSB` and `DSCUSBDrv64` sit in
`ch.rupfizupfi.dscusb.dscusb`, beside a `t24` package for the wireless base station the deck does
not use; `CommandExecutionException` sits one level up, shared. From `0.3.0` `CellValueStream`
implements `LoadCellStream` itself (no adapter) and both backends carry the deck's `Measurement`,
making `device-api` a `compileOnly` dependency of the whole repo, not just its `…dscusb.deck`
package.

**The driver loads the serial-number DLL, not the COM-port one.** Mantracourt ship two:
`MantraASCII2.DLL` over the FTDI virtual COM port, and `DSCUSBDrv.DLL` addressing modules directly
by serial number — the manual's "preferred method", and the one the driver loads. Its only
substantive gain is **up to 127 modules on one bus**; the bench has one cell, so the COM-port path
would cost nothing we use, and it is what any non-Windows driver would have to speak. The device
also has a **continuous output mode** (`SOUT`, XON/XOFF-gated, ASCII protocol only) that would
replace polling outright — unreachable through this DLL, so unexploited (OQ-80). On Linux the device
enumerates as `1781:0BAD`, which no kernel driver binds out of the box
([`decisions.md`](../../decisions.md#bench-platform-windows-native-owner-2026-08-30)).

**Driver contract (decides run outcomes):**

- `READCOMMAND` signals errors by return code only, so a non-finite float alongside a success code
  is a contract violation. `DSCUSB.readCommand` seeds its out-parameter with `NaN` and throws `-800`
  instead of returning it.
- **A transient fault is dropped, not fatal** (`dscusb` 0.3.0, OQ-74 answered). The reader discards
  the reading and reads on within an **80 ms wall-clock budget** — wall clock rather than a retry
  count because the loop never sleeps, so N retries is an unpredictable amount of missing data and
  what has to be bounded is the length of the hole. 80 ms is sized under the deck's 250 ms no-data
  watchdog so a *recovered* burst cannot trip it. Past the budget the original exception is rethrown
  unwrapped, so the trip reason still names the driver's own code. Droppable: `-200`, `-300`,
  `-500`, `-600`, `-700`, `-800`.
- **Terminal, and still ending the stream:** `-1`, `-2`, `-100`, `-400`, and any unknown code —
  stopping with a named cause beats retrying a condition the table cannot reason about. `-100` is
  terminal despite reading as transient: the vendor docs say the DLL *"will halt all processing
  while waiting for a response from the instrument"* with a default timeout of 300 ms, so a `-100`
  has already punched a hole longer than the deck's watchdog when thrown. Retrying cannot save a run
  already over, and dropping it would leave `isReading()` true — bare silence with no named cause.
  The vendor documents only `0/-1/-100/-200/-400` for `READCOMMAND`; the rest come from the driver
  header, so classifying those is judgement about meaning, not observation.
- `LoadCellStream.droppedSampleCount()` is the **only** trace a recovered burst leaves: the stream
  reads on, `lastError()` stays null, and the timestamps show a hole without saying whether the
  driver rejected readings or the bus was just slow. `LoadCellDevice.readData` logs its first rise,
  then at most every 10 s with the delta; `MeasurementLoop`'s incident line carries it, and nothing
  bounds a run's *total* dropped fraction — OQ-81.
- **`stopReading()` returns only once the reader has released the process-global DLL port**, which
  is what `Device.reset()`'s close-then-reopen relies on. A reader abandoned past the join bound
  never closes it — the next open owns it.
- **A stopped stream can never be restarted.** Reconnection must construct a new `CellValueStream`;
  this is why `LoadCellStreamProvider` is a factory, and the constraint the [recovery
  design](safety-and-recovery.md) is built around.
- `isReading()` / `lastError()` expose why the reader stopped. The stream flattens its own throwable
  into a `StreamFailure`, and `LoadCellDevice#getStreamFailure` turns that into a named cause for
  the trip reason — **diagnosis only**: what escalates is always the silence, so a sensor that dies
  without explanation trips identically.

## `usbmodbus.jar` — frequency inverter

Built in-house from the sibling repo (`./gradlew shadowJar`); copy `build/libs/usbmodbus.jar` into
`drivers/` on the bench machine. Never committed, published or put in an image.

The reason is the two third-party **vendor** jars the shadow build bundles —
`CommunicationLib.jar` and `ThesyconUSBLib.jar`, both 2018 — whose licence forbids redistribution; the
`ch.rupfizupfi.usbmodbus` code itself is ours. Those vendor jars are committed to the `usbmodbus`
remote, which stays **private**; making it public would redistribute them. Splitting them out was
declined as not worth it while the repo stays private (see [decisions](../../decisions.md)).

**Driver contract (decides run outcomes):**

- `Cfw11` is `final` (Kotlin), so it cannot be subclassed — that is why `Drive` is
  an interface with a delegating adapter rather than a subclass.
- The no-arg constructor **opens the USB device**. There is no unopened instance, so
  a fresh handle means a new object; this is what `DriveProvider` being a factory
  buys, and what tier 2's `stopWithFreshHandle` relies on.
- **USB arrivals are ignored after open**: that constructor opens a `PnpInertUsbComm`, a `USBComm` subclass with no-op `onAdd`/`onRemove` (BL-198), so a USB arrival never re-initialises a live handle. A replug fails the old handle's next transaction and recovery is a fresh handle (tier 2 or a reconnect; the `usbmodbus` repo's `doc/comms-failure-handling.md`, "Known limits").
- `close()` releases it, and only if that instance opened it. The second constructor takes a
  caller-owned `ModbusUsbHelper` and closes nothing — the seam for a virtual Modbus slave or a test double.
- **Both getter maps have required key sets**: `getMotorData` must carry `speed`, `current`, `voltage`, `torque`; `getControlParameters`
  `start`, `generalEnable`, `useSecondRamp`, `directionIsForward`. A missing key is a poll failure naming it, never a zero reading.
- **`getMotorData` is unscaled** (WEG 0899.5741 §5.4): `speed` rpm, `voltage` V (P0007), `current` tenths of A (P0003, unsigned), `torque` tenths of a **percent of rated torque**, signed (P0009) — not Nm; `FrequencyInverterDevice#toInfo` scales once. `Cfw11` sign-extends only P0009/P0681/P0683 (`SIGNED_REGISTERS`) and refuses ramp times P0100–P0103 outside 0..9990 tenths (documented on `Drive#setSecondSpeedRampTime`, held by `DriveContractTest`, mirrored by `SimulatedDrive`, and gated up front by `TestParameterRules.MAX_RAMP_SECONDS`); reference: the `usbmodbus` repo's `doc/cfw11-parameters.md`.
- **Every comms failure arrives checked**, as one of three types: `NegativeConfirmationException`
  (`"Send Not OK"`, `"Read Not OK"`, `"Timeout"`; never the vendor's "Frame error", as `ModbusUsbRTUMaster.frameError` is hard-coded `false` over USB), `ModbusExceptionResponseException` (the slave answered with an exception PDU) and `ModbusUnexpectedResponseException` (wrong function code or byte count).
  Reads and writes share one path, so a timed-out *write* throws too. Vendor
  retries stay off (`maximumRetries` 0: one attempt at a 100 ms timeout), but a **stop-path** write —
  one only clearing start/general enable, or a zero reference — is retried by `Cfw11` on
  `NegativeConfirmationException`, whole read-modify-write, `STOP_PATH_ATTEMPTS` (3) in all.
- **None of the three is a `RuntimeException`** and Kotlin lets them cross into Java undeclared, so
  both poll loops — `FrequencyInverterDevice#readData` and `LoadCellDevice#readData` — catch `Exception`:
  a driver failure idles that tick and the poll thread survives, the drive logging once per outage.
  `Cfw11Drive` wraps all three unchecked in `Cfw11CommunicationException`, so every other call site
  sees a `RuntimeException` — a `DriveCommunicationException` since 1.4.0, logged by its cause in
  the info poll (whose `Exception` catch stays for older jars); a closed `Cfw11Drive` refuses calls. Safety paths catch `Throwable` (`MotorSafetyController#verifyStopped`,
  `commandStop`); traced in the `usbmodbus` repo's `doc/comms-failure-handling.md`.
- **A value too wide for its 16-bit register is refused**, unchecked (`Cfw11RangeException`), before
  any register is written — a speed reference past 4 × synchronous speed included, a refusal rather than full-scale reverse. The synchronous speed (P0681/P0683: 8192 = synchronous) is derived at open from P0402/P0403 as 120 × f / poles and logged at INFO; inconsistent values make every non-zero speed reference throw `Cfw11MotorRatingException` (unchecked) at energize, while a zero reference and every stop still go out (bench check OQ-90).
- **A frame carrying fewer registers than asked for is refused**, unchecked
  (`Cfw11ProtocolException`), never yielding a reading — every read shares one path.
- **`enableLinkLossStop` writes P0314**, the serial watchdog (`Cfw11#armSerialWatchdog`). Per WEG
  0899.5741 §3 it is 0.0–999.0 s, default 0.0 = off, CFG (writable only with the motor stopped);
  one decimal travels as tenths (§5.4.3: P0100 = 1.0 s is written as 10), so the driver rounds up
  to 100 ms. It refuses to arm unless P0313 is 1, 2 or 5 (an action that stops the motor). P0313
  is CFG too, so both setters write only a changed value and read it back: a resume over a
  coasting shaft re-sends identical values and is never refused. **Bench check owed (OQ-85):** the manual scopes P0314,
  alarm A128 and fault F228 to the RS-232/RS-485 interface ("Serial" visibility) while this driver
  talks over the drive's USB port. On the bench: confirm the write is accepted, then pull the USB
  cable mid-run and watch for A128 and the coast.
- **`readControlSource` reads P0680 bit 12** (`Cfw11#isRemote`), the drive's actual LOC/REM state —
  WEG 0899.5741 §3, Table 3.3 (p. 14): 0 local, 1 remote. One read, no retry, never `UNKNOWN`. Per
  this bench's P0220 setup, LOCAL is the deck over USB and REMOTE the push-button board (owner, 2026-10-19).
  `getControlParameters`' extra `localRemote` key is P0682 bit 4, the LOC/REM *request* (Table 3.4,
  p. 15), acted on only when P0220 selects serial; no deck code reads it. **Bench check owed (OQ-87):**
  REMOTE at the board, restart the deck, expect `SKIPPED` and no write; LOCAL, restart, it de-energizes.

`CommunicationLib.jar` also holds `devicemanager.VirtualDeviceConnection`, an in-memory Modbus
slave reporting vendor `WEG` / product `VDW-00`. The deck does not use it: no vendor code loads in
dev, so the simulated provider declares its own identity for `FrequencyInverterCheck` (OQ-44). The
injecting constructor above is what a wire-level fidelity path would need; nothing consumes it yet.

**Transaction cost** of the gated `Cfw11Drive` (one 100 ms reply timeout each; the `usbmodbus`
repo's `doc/cfw11-parameters.md` owns it, and its `doc/transaction-scheduler.md` the lanes):

| Call | Telegrams |
|---|---|
| `stop()` | one FC16 write of P0682..P0683: start, general enable and the reference cleared together, from the cached control word (a read first only on a handle that never read P0682) |
| `getMotorData()` | one 8-register read, P0002..P0009; three reads once the drive refuses that block (BL-177) |
| the info poll | that snapshot plus one P0682 read, both in the STATUS lane, queued behind any stop |
| another control-word write | one write with a trusted cached word, else a read and a write |
| open | two reads, P0682 then P0402/P0403 |

Its `commandbus.CommandChain` is unused: it serialises writes only, is fire-and-forget, and cannot
carry a return value or an exception, so it cannot back a stop that must know whether the motor
stopped
([`testrunner-architecture.md`](testrunner-architecture.md#constraints-the-structure-keeps)).

## Known limits

- Only *consecutive* driver faults are budgeted; nothing bounds a run's total dropped fraction (OQ-81).
- The DSCUSB's continuous output mode would remove load-cell polling; it is unexploited and unverified on this variant (OQ-80).
