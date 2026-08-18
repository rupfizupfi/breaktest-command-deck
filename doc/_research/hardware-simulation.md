> Branch: `dev-split` — external research captured 2026-08-17.
> **Nothing evaluated here is adopted** — see [`README.md`](README.md).

# Running the bench without hardware

**Goal evaluated:** boot `command-deck` and exercise a full test run with no
load cell, drive, or relay attached. Nothing below is implemented.

How the devices work today is owned by
[`../03-backend/hardware-integration.md`](../03-backend/hardware-integration.md);
this file only records what the research adds.

---

## Contents

- [The finding that reframes the problem: only one device is a COM port](#the-finding-that-reframes-the-problem-only-one-device-is-a-com-port)
- [Second finding: a WEG Modbus simulator is already on the classpath](#second-finding-a-weg-modbus-simulator-is-already-on-the-classpath)
- [Why mocking the vendor types will not work](#why-mocking-the-vendor-types-will-not-work)
- [Java 26: one flag to add now](#java-26-one-flag-to-add-now)
- [Ranked path](#ranked-path)
- [Do not use](#do-not-use)
- [Protocol references (all public, no NDA)](#protocol-references-all-public-no-nda)

## The finding that reframes the problem: only one device is a COM port

Established by decompiling the local jars (**HIGH** — direct inspection, not
vendor docs):

| Device | Actual transport | Virtual COM port relevant? |
|---|---|---|
| Four-way relay | jSerialComm on a CH9102 USB-UART, 115200-8N1 | the only real COM port |
| CFW11 drive | `ch.rupfizupfi.usbmodbus.Cfw11` → `ModbusUsbRTUMaster` → `USBComm` → **`de.thesycon.usbio`** kernel driver — Modbus-RTU framed over **raw USB bulk pipes** | **no** |
| Load cell | `ch.rupfizupfi.dscusb.CellValueStream` → **jnr-ffi** → **`DSCUSBDrv64.dll`**, addressed by serial number | **no** |

**Consequence: do not buy or install a virtual serial-port driver.** It would
serve at most one of three devices — and not even that one, because port
discovery filters on the descriptive name `CH9102`
(`command-deck/src/main/java/ch/rupfizupfi/deck/device/relayswitch/FourWayRelaySwitch.java`,
already tracked as **OQ-46**), which no virtual port will match. Once that code
has to change anyway, an in-process fake is strictly cheaper.

> ⚠️ **Supply-chain warning.** The domain `com0com.com` dominates search
> results and looks official. It is not — it self-describes as unaffiliated
> with the com0com project and distributes a "Windows 11 Signature Patch": a
> repackaged kernel driver signed by *FuJian Newland*, a barcode-scanner OEM
> with no connection to com0com. Re-signing someone else's GPL kernel driver
> with an unrelated vendor's certificate is the textbook BYOVD staging pattern.
> **Do not download from it.** (The genuine com0com is abandonware — signed
> build 2017, and in the cohort de-trusted by Microsoft's April 2026 removal of
> trust for cross-signed kernel drivers.)

## Second finding: a WEG Modbus simulator is already on the classpath

`lib/usbmodbus.jar` contains `devicemanager/VirtualDeviceConnection` — an
in-memory Modbus slave with its own `main()`, implementing read/write coils and
holding registers, `processReadDeviceIdentification`, and WEG-proprietary
exchange modes. Its FC43 identity constants are vendor `WEG`, product code
`VDW-00`. It is WEG's own device-manager stack repackaged. (**HIGH** —
string-extracted from the class files; **MEDIUM** on the provenance being WPS
specifically.)

Two consequences: a register-level CFW11 fake needs very little new code, and
the `VDW-00` identity is *already distinguishable* from a real drive, which is
exactly what a simulator should return (see
[`machine-safety.md`](machine-safety.md)). It is also evidence bearing on
**OQ-43** — whose licence governs that jar.

---

## Why mocking the vendor types will not work

| Type | Blocker |
|---|---|
| `com.fazecast.jSerialComm.SerialPort` | Not `final`, but both constructors are private, it implements no interface, and factories are static. Worse, **mockito#3085**: mocking it fails with `ClassNotFoundException: android/app/Application`, because `SerialPort` delegates every I/O call to an `AndroidPort` field whose class hard-imports the Android SDK. Broke at jSerialComm 2.10.3, open since 2023. `mockito-inline` does not help — it *is* the inline mock maker failing. Fazecast#528 proposed extracting an interface; no maintainer response. |
| `ch.rupfizupfi.usbmodbus.Cfw11` | `public final` — cannot be subclassed. An interface is mandatory. |
| `ch.rupfizupfi.dscusb.CellValueStream` | Reached through a native DLL; no seam at all. |

So the seam has to be written, not mocked. Every open-source project that
solved this converged on the same three pieces — and **JMRI is the closest
analogue** (model-railway hardware, dozens of serial protocols, Java):

1. **A narrow own-domain interface**, deliberately smaller than the vendor API,
   so nothing outside one adapter package imports the vendor namespace.
2. **A thin composition adapter** holding a private vendor object — never
   subclass, never mock the vendor type.
3. **A hand-written fake that speaks the real protocol**, not a method-level
   mock. JMRI's simulators wire `PipedInputStream`/`PipedOutputStream` and
   answer with protocol-correct frames on a background thread.

| Project | Selection mechanism | Worth copying |
|---|---|---|
| **JMRI** | user picks a "Simulator" connection type | the interface + composition + protocol-simulator shape |
| **ros2_control** | `mock_components/GenericSystem` plugin named in config — one line, not a code change | **`disable_commands`: deliberate fault injection.** The ability to simulate a *broken* device is where most of the value is |
| **Apache PLC4X** | connection-string scheme | two fidelity tiers: a semantic `simulated://` driver *and* a byte-level test transport |
| **Ardulink-2** | URI scheme (`ardulink://virtual-random`) | config-not-code switching |

NI's hardware-abstraction-layer guidance lists instrument simulation as a
first-class HAL feature. A systems-integrator write-up (DMC, 2025-12) adds the
honest pitfalls: **over-abstraction too early** — *"starting too early without
multiple hardware options can lead to unnecessary complexity"* — leaky
abstractions from hardware variability, and overhead in time-critical paths.

---

## Java 26: one flag to add now

jSerialComm 2.11.4 is current and pinned in `build.gradle`. It is JNI, not
Panama, and issue #619 (asking about Panama, open since 2025-12) is unanswered.
JEP 472 made `System::loadLibrary` restricted in JDK 24; the default is still
`--illegal-native-access=warn` in JDK 26, so nothing is broken today — but when
a future JDK flips the default to `deny`, this becomes a hard startup failure.

**Add `--enable-native-access=ALL-UNNAMED`** to `bootRun` JVM args and the
Docker entrypoint. Note the module-name form (`...=com.fazecast.jSerialComm`)
**will not work**: a Boot fat jar puts every dependency on the classpath as an
unnamed module. Confidence **HIGH** on both underlying facts, **MEDIUM** on the
Spring-Boot-specific combination — no source states it explicitly.

⚠️ **Unverified, worth five minutes:** whether an `Enable-Native-Access`
manifest attribute is honoured by Spring Boot's fat-jar launcher. It is
demonstrably ignored by GraalVM native-image and jbang, so prefer the explicit
JVM argument.

---

## Ranked path

| # | Step | Effort | Notes |
|---|---|---|---|
| 1 | **Three interfaces + a `simulated` profile** | 1–2 days | `LoadCell` over `CellValueStream` is **three methods** — a fake replaying the existing force CSVs or generating a ramp at 50 Hz is 1–2 hours, pure Java, and runs headless on Linux, which also removes the Windows-x64-only constraint. `FrequencyConverter` over `Cfw11` (~20 methods) can be backed by `VirtualDeviceConnection`. Include a fault-injection switch. **Also fixes the seven `new` sites** that currently prevent any single switch point. |
| 2 | **Device-identity + safe-state startup checks** | ½ day | This is **OQ-44**; specifics in [`machine-safety.md`](machine-safety.md). |
| 3 | **Record and replay** | ~1 day | Log every drive and load-cell call plus result during one real session to JSON Lines; replay as fixtures and golden traces. `pytest-reserial` is the reference design (JSON Lines, one file per test module). **There is no Java equivalent** — a GitHub search returned zero results — but at this size it is ~100 lines. The load-cell half is nearly free: force traces are already persisted via `cms/src/main/java/ch/rupfizupfi/deck/filesystem/CSVStoreService.java`. |
| 4 | **Wire-level coverage, only if needed** | — | **j2mod 3.3.0** (Apache-2.0) `createTCPSlave(..., useRtuOverTcp=true)` gives byte-exact RTU frames and CRCs over a loopback socket — no driver, no admin rights, CI-able. The only reason to involve TCP at all. |
| 5 | **Real UART testing, only if the RS485 kit is ever fitted** | ~$20–60 | Two USB-serial dongles wired back-to-back. Immune to Secure Boot and driver signing because no new driver is installed. Software alternative: HHD's virtual ports, which are **UMDF 2.x user-mode** and so sidestep kernel signing entirely. |

**Honest limitation of steps 1–3:** they test your logic, not the wire
protocol. They will never catch a CRC, byte-order, or framing bug. Modbus RTU
frames are delimited by a ≥3.5-character silent interval — WEG clamps this to
4.010 ms at 9600 baud — and **no userspace Windows simulator honours
sub-millisecond inter-frame timing reliably.** Timing-sensitive framing bugs
are structurally outside what any of this can find. Budget for that rather than
assuming coverage.

## Do not use

Abandonware, wrong tool, or licence hazard:

- **com0com**, and above all anything from `com0com.com` (see warning above).
- **ModbusPal** (2020, depends on the dead RXTX), **jamod** (2010),
  `ModbusSlaveSimulation` (archived 2025-12), **Modbus Mechanic** (it is a
  *master*, not a slave simulator).
- **Modbus4J** (GPL-3) and **nifty-modbus** (GPL-2) — licence hazard for a
  closed product. Prefer **j2mod** (Apache-2.0) or **digitalpetri modbus**
  (EPL-2.0).
- **Eltima / Electronic Team** virtual serial products: claims Windows 11,
  never mentions Secure Boot or HVCI, support matrix still lists Windows 7.
  Note `virtual-serial-port.org` and `reviews.electronic.us` are Eltima-owned
  properties presenting as independent comparisons.

## Protocol references (all public, no NDA)

Useful if a faithful simulator is ever written — the register map is confirmed
twice over, once from WEG's manual and once from the constants in
`lib/usbmodbus.jar`:

- Holding-register address **equals** the parameter number, zero-offset.
  `P0680` logic status (RO), `P0681` motor speed (13-bit, `0x2000` =
  synchronous), `P0682` control word, `P0683` speed reference.
- ⚠️ **`P0680`'s bit layout changed between firmware revisions** (bit 0 became
  STO, bit 2 became Fire Mode), so a faithful simulator must know the firmware
  — readable via FC43 object 02h.
- Load cell speaks **MantraASCII** only: `115200 8N1`, frames
  `!<3-digit station>:<command><CR>`, no checksum, fixed station 001. The
  application reads exactly one parameter, `SYS`.
- No DSCUSB simulator exists from the vendor or on GitHub (**MEDIUM-HIGH** as
  an absence finding), and nobody has published a from-scratch MantraASCII
  implementation.

Sources: [WEG 0899.5741](https://static.weg.net/medias/downloadcenter/h58/h90/WEG-cfw11-rs232-and-rs485-manual-0899.5741-en.pdf) ·
[WEG programming manual V7.1X](https://static.weg.net/medias/downloadcenter/he7/h71/WEG-CFW11-programming-manual-10004274148-en.pdf) ·
[DSC/DLC user manual](https://www.mantracourt.com/wp-content/uploads/2025/03/DSC-DLC-User-Manual-01-01.pdf) ·
[mockito#3085](https://github.com/mockito/mockito/issues/3085) ·
[jSerialComm#528](https://github.com/Fazecast/jSerialComm/issues/528) ·
[JEP 472](https://openjdk.org/jeps/472) ·
[JMRI simulator adapter](https://github.com/JMRI/JMRI/blob/master/java/src/jmri/jmrix/nce/simulator/SimulatorAdapter.java) ·
[ros2_control mock components](https://control.ros.org/rolling/doc/ros2_control/hardware_interface/doc/mock_components_userdoc.html) ·
[DMC on HALs](https://www.dmcinfo.com/blog/39967/why-hardware-abstraction-layers-hal-are-essential-for-scalable-test-systems/) ·
[pytest-reserial](https://github.com/bessman/pytest-reserial)
