> Branch: `feat/simulated-bench` — captured 2026-08-18.

# The two local driver JARs

## Purpose

`lib/dscusb.jar` and `lib/usbmodbus.jar` are built from sibling repos outside
this one, so nothing here tracks their source. This page owns where each comes
from, what its build needs, and the parts of its behaviour that decide run
outcomes. The classpath wiring is
[`gradle-build.md`](../02-modules/gradle-build.md); how the wrappers are used is
[`hardware-integration.md`](hardware-integration.md).

**Both are optional to build and mandatory to run.** Nothing in this repo
imports either: each jar is a self-contained deck plugin that implements
`ch.rupfizupfi.deck:device-api` (the `device-api/` composite build here) and
registers its provider beans through its own Spring Boot auto-configuration, so
the deck knows the drivers only at runtime. `deck.hardware.mode=real` refuses to
start without both provider beans — see
[`driver-api-extraction.md`](../06-feature-work/virtual-devices/driver-api-extraction.md).

## Contents

- [At a glance](#at-a-glance)
- [`dscusb.jar` — load cell](#dscusbjar--load-cell)
- [`usbmodbus.jar` — frequency converter](#usbmodbusjar--frequency-converter)
- [Open questions](#open-questions)

## At a glance

| | `dscusb.jar` | `usbmodbus.jar` |
|---|---|---|
| Provides | `ch.rupfizupfi.dscusb.dscusb.CellValueStream`; `Measurement` and `CommandExecutionException` one level up | `ch.rupfizupfi.usbmodbus.Cfw11` |
| Deck plugin package | `ch.rupfizupfi.dscusb.deck` — `CellValueStreamAdapter`, `DeckLoadCellAutoConfiguration` | `ch.rupfizupfi.usbmodbus.deck` — `Cfw11Drive`, `DeckDriveAutoConfiguration` |
| In git | **tracked** | **gitignored** (`.gitignore:36`), licence-restricted |
| Sibling repo | `dscusb` | `usbmodbus` |
| Buildable on this machine | yes, from a clean checkout | yes, from a clean checkout |
| Reaches hardware via | jnr-ffi → `DSCUSBDrv64.dll`, by serial number | bundled vendor libraries |

Both repos build on Gradle 9.7 / Kotlin 2.4.10 / gradleup shadow / JVM target 26, and the
jars in `lib/` are reproducible from their committed source. Both repos
`includeBuild("../breaktest-command-deck/device-api")` and implement the deck's
contract in a `deck` package (`compileOnly` on the contract and on
`spring-boot-autoconfigure`, so neither is bundled into the shadow jar — a copy
of the contract classes inside a driver jar would shadow the deck's own).
**Their compile against the live contract is the conformance guarantee**: a
`device.api` change surfaces as a compile error on the next driver build, and
both repos therefore need the deck checkout as a sibling directory.

The deck loads every jar in `lib/` runtime-only
([gradle-build.md](../02-modules/gradle-build.md#driver-plugin-jars-lib)), so a
missing jar is a *startup* failure, never a compile failure.

## `dscusb.jar` — load cell

`./gradlew shadowJar` in the sibling repo, then copy `build/libs/dscusb.jar` over
`lib/dscusb.jar` here. There is no publish or install task on either side; the copy
is manual.

**The package layout is split, and only part of it moved.** `CellValueStream`,
`Connection`, `DSCUSB` and `DSCUSBDrv64` sit in `ch.rupfizupfi.dscusb.dscusb`,
beside a `t24` sibling package for the wireless base station that the deck does not
use. `Measurement` and `CommandExecutionException` are shared by both backends and
stay one level up in `ch.rupfizupfi.dscusb`. `CellValueStreamAdapter` (in this
repo's `deck` package) imports from both, which is the whole blast radius of that
move — the deck owns its own `Measurement`, so nothing over there sees it.

**Driver contract, and it decides run outcomes:**

- `READCOMMAND` signals errors by return code only, so a non-finite float
  alongside a success code is a contract violation. The driver throws instead of
  returning it.
- That throw exits the reader loop, which closes the port and records the cause.
  So one bad sample **ends the stream**, and the run then dies on the no-data
  watchdog — the trade recorded as OQ-74.
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

## `usbmodbus.jar` — frequency converter

Gitignored, and **must stay that way** — the licence does not permit
redistribution. A fresh clone builds without it but cannot drive the machine.
Vendor, licence holder and required version are recorded nowhere (OQ-43); only
the project owner can close that.

The blocker is narrower than "the jar". It is the **vendor** libraries the
sibling repo's shadow build bundles — `CommunicationLib.jar` and
`ThesyconUSBLib.jar`, both 2018 — not the `ch.rupfizupfi.usbmodbus` code.
Splitting them apart would let the project half be committed, which is worth
raising when OQ-43 is answered.

Note the asymmetry to raise alongside OQ-43: those vendor jars are themselves
**committed** to the `usbmodbus` remote. If that remote is public, the
"non-redistributable" position is already compromised upstream.

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
  undeclared. `CFW11Device#readData` catches only `DriveUnavailableException` and
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
writes only, is fire-and-forget, and cannot carry a return value or an
exception, so it cannot back a stop that must know whether the motor stopped.
Reasoning in
[`hardware-layer-redesign`](../06-feature-work/hardware-layer-redesign/README.md#what-stays-unchanged-deliberately).

## Open questions

| OQ | Topic |
|---|---|
| OQ-43 | `usbmodbus.jar` provenance — owner-owed |
| OQ-74 | One non-finite reading ends the stream, and therefore the run |
