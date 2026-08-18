> Branch: `dev-split` — captured 2026-08-17.

# The two local driver JARs

## Purpose

`lib/dscusb.jar` and `lib/usbmodbus.jar` are built from sibling repos outside
this one, so nothing here tracks their source. This page owns where each comes
from, what its build needs, and the parts of its behaviour that decide run
outcomes. The classpath wiring is
[`gradle-build.md`](../02-modules/gradle-build.md); how the wrappers are used is
[`hardware-integration.md`](hardware-integration.md).

**Both are optional to build and mandatory to run.** Nothing in `src/main`
imports either; only the adapters in the optional `drivers` source set do, so a
clone without them compiles. `deck.hardware.mode=real` then refuses to start
without both provider beans — see
[`driver-api-extraction.md`](../06-feature-work/virtual-devices/driver-api-extraction.md).

## Contents

- [At a glance](#at-a-glance)
- [`dscusb.jar` — load cell](#dscusbjar--load-cell)
- [`usbmodbus.jar` — frequency converter](#usbmodbusjar--frequency-converter)
- [Open questions](#open-questions)

## At a glance

| | `dscusb.jar` | `usbmodbus.jar` |
|---|---|---|
| Provides | `ch.rupfizupfi.dscusb.CellValueStream`, `Measurement` | `ch.rupfizupfi.usbmodbus.Cfw11` |
| In git | **tracked** | **gitignored** (`.gitignore:36`), licence-restricted |
| Sibling repo | `dscusb` | `usbmodbus` |
| Buildable on this machine | only from that repo's **uncommitted** tree (OQ-75) | **no** (OQ-76) |
| Reaches hardware via | jnr-ffi → `DSCUSBDrv64.dll`, by serial number | bundled vendor libraries |

The `drivers` source set is registered only when **both** are present, so a
missing jar is a *startup* failure, never a compile failure.

## `dscusb.jar` — load cell

Building it needs **Gradle 9.7, Kotlin 2.4.10, JVM target 26 and gradleup
shadow**. The older johnrengelman-shadow-on-Gradle-8 setup cannot run on the JDK
installed here — that is what blocked the rebuild until it was migrated.

**That migration is itself uncommitted** (OQ-75). `dscusb` HEAD still carries
Kotlin 2.1.10, johnrengelman shadow 8.1.1, `jvmToolchain(23)` and no wrapper at
all, so a clean checkout does not build here either — the same position
`usbmodbus` is in. Only the local working tree does, and the shipped jar came
from it, so the binary cannot be reproduced from that repo's history.

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

`CommunicationLib.jar` is also where `devicemanager.VirtualDeviceConnection`
lives — an in-memory Modbus slave reporting vendor `WEG` / product `VDW-00`.
The deck does **not** plan to use it: with the drivers optional, no vendor code
loads in dev at all, so the simulated provider declares its own identity for
`Cfw11Check` (OQ-44). It stays relevant only to the optional wire-level fidelity
path, which would need `Cfw11` to accept an injected transport.

That repo still carries the Gradle 8 / johnrengelman-shadow setup `dscusb` had
to leave behind, so it **cannot be rebuilt here today** (OQ-76). Nothing needs
it yet — but the `Drive` seam, tier 2's fresh-handle behaviour and OQ-50 all sit
on that API.

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
| OQ-75 | The shipped `dscusb.jar`'s source is uncommitted |
| OQ-76 | The `usbmodbus` repo cannot be built on the installed JDK |
