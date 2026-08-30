> Branch: `docs/dscusb-serial-findings` — researched 2026-08-29. **Nothing here is being
> built.** Recorded so the option can be picked up later without redoing the research.

# Future option: driving the DSCUSB over its serial ASCII protocol

## Purpose

The load cell is reached today through `DSCUSBDrv64.dll`, one of the two reasons
`command-deck` is a Windows program
([`driver-jars.md`](../../03-backend/driver-jars.md#both-drivers-are-windows-only-and-that-decides-the-deployment)).
The device also speaks a plain ASCII protocol over a serial port, so ~200–300 lines of
jSerialComm — already a `command-deck` dependency — would replace the vendor DLL with
portable Java.

**This is not being built.** The hardware controller stays a Windows PC and the deck runs
natively on it; that is decided, see
[`bench-deployment.md`](../../05-ops/bench-deployment.md). This page holds the research
behind the option that was *not* taken, plus one device capability worth exploiting
independently of it — [continuous output](#continuous-output-mode-worth-having-anyway),
tracked as OQ-80.

What the rewrite would buy: no vendor blob and no per-architecture native library, a
driver that runs anywhere jSerialComm does, and access to continuous output. What it
would **not** buy: a bench PC you can throw away. A Linux container still needs a Linux
host physically wired to the bench, and the drive is a separate problem —
see [the other half](#the-load-cell-is-only-half-of-a-linux-deck).

## Contents

- [We are using the optional DLL](#we-are-using-the-optional-dll)
- [The protocol](#the-protocol)
- [The Linux blocker: the DSCUSB is not `0403:6001`](#the-linux-blocker-the-dscusb-is-not-04036001)
- [Continuous output mode — worth having anyway](#continuous-output-mode--worth-having-anyway)
- [Prior art: one unmaintained Python wrapper](#prior-art-one-unmaintained-python-wrapper)
- [Ruled out: FTDI D2XX and libftdi](#ruled-out-ftdi-d2xx-and-libftdi)
- [Ruled out: Modbus](#ruled-out-modbus)
- [The load cell is only half of a Linux deck](#the-load-cell-is-only-half-of-a-linux-deck)
- [What is verified and what is inferred](#what-is-verified-and-what-is-inferred)
- [Open questions](#open-questions)

## We are using the optional DLL

Mantracourt's DSCUSB Advanced User Manual documents **two** libraries.
`MantraASCII2.DLL` talks over the FTDI **virtual COM port**; `DSCUSBDrv.DLL` talks
directly over USB via the kernel-level driver, addressing modules by serial number, and
the manual calls it "the preferred method". The driver loads the latter
(`DSCUSBDrv64.dll`).

Its one substantive advantage is addressing **up to 127 modules by serial number**. The
bench has one load cell, so the COM-port path costs nothing we actually use — and the
manual's own framing is that writing the protocol yourself is "your only option if you
cannot utilise the standard Windows DLLs", which is precisely the Linux case.

**No Linux port of the DSCUSB driver exists, official or third-party.** Mantracourt ships
Windows-only across the DSC/DSCUSB/T24 range: the DLL page (v2.2, 2022-12-08) states "All
operating systems from Windows 95 upwards are supported", it is freeware with **no
source**, and its licence forbids modification and repackaging. No `.so`, no Python
package, no Java, no macOS, no Raspberry Pi. The only cross-platform product in the range
is the **B24 Bluetooth** line — BLE, different hardware, not a route. Rebadges of the
identical hardware (SMD Sensors E370, Novatech, Applied Measurements, Micron Meters,
Massload, ADM) ship the same Windows-only software.

## The protocol

Framing, from the vendor manual: `!` framing character, 3-digit station address, `:`
separator, up-to-4-character case-insensitive command identifier, access code, `<CR>` —
e.g. `!001:SYS?<CR>`. Serial line is **115200 baud, 8N1**, and the DSCUSB's station
address is **fixed at 001**.

**There is no checksum.** The manual notes the slave uses the separator as its only extra
validity check beyond the framing. Three response types: ACK, ACK-with-data (a decimal
followed by `<CR>`, e.g. `123.456<CR>`), and NAK.

Parameters: `SYS` (main output, float, read-only), `CELL`,
`SRAW`, `TEMP`, `MVV`, `CMVV`, `STAT`, `FLAG`, `SZ`, `STN`, `BAUD`, `RATE`, `DP`/`DPB`.
Commands: `RST`, `SNAP`, `RSPT`, `SCON`, `SCOF`.

**Effort: ~200–300 lines.** Open `/dev/ttyUSB*` or a Windows COM port at 115200 8N1,
write `!001:SYS?<CR>`, read a decimal terminated by `<CR>`, plus NAK/error handling and
reconnect. No checksum, no framing library, no CRC, no new dependency.

## The Linux blocker: the DSCUSB is not `0403:6001`

The one thing nobody had spotted, and it would have cost a bench afternoon to discover:
the DSCUSB does **not** use FTDI's default `0403:6001`. Its USB ID is **`1781:0BAD`**
("Mantracourt Load Cell" in `usb.ids`, vendor `1781 Multiple Vendors`), independently
corroborated by the manual's own Windows registry workaround `"IgnoreHWSerNum17810BAD"` —
the format of that key is `IgnoreHWSerNum<VID><PID>`.

That PID is **absent from the Linux kernel's `ftdi_sio` id table** (`ftdi_sio_ids.h`
carries `0x1781` only as `TELLDUS_VID`). Consequence: **a DSCUSB does not create
`/dev/ttyUSB0` on a stock Linux box.** Nothing enumerates; the port simply is not there.

The fix is one line, and any of three forms works:

```sh
echo 1781 0bad > /sys/bus/usb-serial/drivers/ftdi_sio/new_id   # or a udev rule
modprobe ftdi_sio vendor=0x1781 product=0x0bad
```

But that is **host** state, not application state — it has to be provisioned on every
machine, and a container would additionally need the tty passed through
(`--device=/dev/ttyUSB0`). A portable driver would still leave a non-portable host
prerequisite behind it.

## Continuous output mode — worth having anyway

The DCell & DSC manual — named by the DSCUSB manual as the authority for the full
MantraASCII protocol — documents a **continuous output mode, ASCII protocol only**:
`SOUT` is broadcast continuously at the configured output rate and toggled with standard
**XON (0x11) / XOFF (0x13)**. Its stated limitation is that it only works one-to-one, a
single unit on the bus — **which is exactly the DSCUSB case**.

This would **remove polling entirely**. Mantracourt rate the DSCUSB at **200
samples/second**; at 115200 baud with ~10-byte replies a continuous stream is comfortable,
whereas request/response at 200 Hz is tight. It is reachable only over the ASCII path, so
it is unavailable through the DLL the driver uses today — which is why it is filed as
OQ-80 rather than as an easy win.

**Unverified:** a search snippet claimed `STN=998` enables streaming without XON. This
could **not** be confirmed in the manual and must be treated as unconfirmed until someone
reads it on the wire.

## Prior art: one unmaintained Python wrapper

`https://github.com/mikelitu/DSCUSB` — 3 files, ~100 lines, **no licence** (so no rights
to use the code), 0 stars, last pushed 2021-07-26. It is a `ctypes.WinDLL` wrapper around
`MantraASCII2Drv.dll` requiring 32-bit Python, so it is **not** a Linux path. Its value is
as an API and error-code reference — worth keeping as a sanity check against any error
taxonomy we write:

| Call | |
|---|---|
| `OPENPORT(port, 115200)` / `CLOSEPORT()` / `VERSION()` | port lifecycle |
| `READCOMMAND(1, "SYS", &float)` | station 1, parameter, out-param |

| Code | Meaning |
|---|---|
| −1 | invalid argument |
| −2 | port open/close failure |
| −100 | no response |
| −200 | invalid station |
| −300 | invalid checksum |
| −400 | NAK |
| −500 | invalid reply length |

Other Mantracourt repos found (`jmcel12/b24-loadcell`, `Equalle/broadweigh-wind-monitor`)
are for different products and irrelevant.

## Ruled out: FTDI D2XX and libftdi

Viable, but the wrong trade. `libftd2xx` ships for Linux x86/x86_64/ARM including
Raspberry Pi, and `FT_SetVIDPID` is supported on Linux/macOS — which is needed, since
D2XX only opens devices in its built-in VID/PID table and `1781:0BAD` is not one.

**But** FTDI's own Linux ReadMe requires unloading or blacklisting `ftdi_sio` and
`usbserial` first, because the kernel VCP driver claims the device. That is host-level
kernel-module surgery — strictly worse than the one-line id registration we would be
escaping — and you would still write the ASCII protocol on top, D2XX being only a byte
pipe. `libftdi` (LGPL, libusb) has the identical kernel-module conflict.

**Recommendation: don't.** `ftdi_sio` + jSerialComm needs no blob and no per-architecture
native library.

## Ruled out: Modbus

On this product family, ASCII vs MANTRABUS vs MODBUS is an **order-time firmware/SKU
variant, not a runtime switch** — product codes `DLCPKASC`/`DLCPKMAN`/`DLCPKMOD`,
`DSC4AS`/`DSC4MA`/`DSC4MB`. The DSCUSB Advanced Manual contains zero occurrences of
"Modbus" or "Mantrabus": our unit is the ASCII variant, fixed station 1, fixed 115200.

No successor product with Linux support was found. The rest of the family (DSC/DCell
cards) is RS232/RS485, which is a rewiring change, not a software one.

## The load cell is only half of a Linux deck

`usbmodbus` has its own driver-free path — the serial Modbus classes already bundled in
`CommunicationLib` (`ModbusSerialRTUMaster`, `ModbusSerialHelper`,
`communications.serial.SComm`). Two things make it much worse than the load-cell side:
it is a **wiring** change (the CFW11's RS485 terminals are not its USB port), and the
bundled 2015 `purejavacomm` calls `Native.setPreserveLastError`, removed in JNA 5.x, so
it would `NoSuchMethodError` against the pinned JNA 5.19.1.

So a serial DSCUSB alone does not produce a Linux deck. Both drivers plus rewiring do —
and even then the bench still needs a Linux host wired to it.

## What is verified and what is inferred

Honest limits, which a later reader must not lose:

| Claim | Status |
|---|---|
| USB ID is `1781:0BAD`; absent from the kernel `ftdi_sio` table | **Verified** (`usb.ids`, `ftdi_sio_ids.h`, corroborated by the manual's registry key) |
| Protocol framing, 115200 8N1, fixed station 001, no checksum | **Verified** in the vendor manual |
| Continuous output / XON-XOFF as described | **Documented for DCell & DSC** — not confirmed on the USB variant specifically |
| "It is a plain FTDI UART behind them" | **Inference** from Mantracourt's docs, not a confirmed report |
| `STN=998` streams without XON | **Unverified** — search snippet only, not found in the manual |
| Anyone has ever run a DSCUSB on Linux | **No public first-hand report exists** |

Two things need real hardware before any of this is actionable: (a) that the device
enumerates as `ttyUSB` once `1781:0bad` is bound, and (b) that continuous/XON mode
behaves on the **USB** variant as the DCell & DSC manual describes.

## Open questions

| OQ | Topic |
|---|---|
| OQ-80 | Continuous output mode would remove polling — unexploited, and unverified on the USB variant |
| OQ-79 | The deck `docker` profile can never drive the bench; whether it is retired is open |
