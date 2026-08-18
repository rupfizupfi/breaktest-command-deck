> Branch: `dev-split` — captured 2026-08-17.

# Hardware integration

## Purpose

Document the path from a physical sensor or actuator to a STOMP frame in the browser:
which port carries the bytes, which provider speaks the protocol, which class wraps it,
which thread polls it, which broadcaster publishes it. All hardware code lives in
`:command-deck`; cms is hardware-agnostic.

## Contents

- [Diagram — driver -> broadcaster -> WebSocket](#diagram--driver---broadcaster---websocket)
- [Narrative](#narrative)
  - [The device API and its providers](#the-device-api-and-its-providers)
  - [The `Device` base class](#the-device-base-class)
  - [`DeviceService` — singleton coordinator](#deviceservice--singleton-coordinator)
  - [Load cell — `DSCUSB` over USB](#load-cell--dscusb-over-usb)
  - [Frequency converter — `CFW11` over USB Modbus](#frequency-converter--cfw11-over-usb-modbus)
  - [4-way relay — serial via `jSerialComm`](#4-way-relay--serial-via-jserialcomm)
  - [Pre-test checks](#pre-test-checks)
  - [Topic summary](#topic-summary)
- [Where to look in the code](#where-to-look-in-the-code)
- [Open questions](#open-questions)

## Diagram — driver -> broadcaster -> WebSocket

```mermaid
flowchart LR
    subgraph Hardware["Physical hardware (real mode only)"]
        DSC["DSCUSB load-cell (USB)"]
        CFW["WEG CFW11 frequency converter (USB Modbus)"]
        REL["4-way relay CH9102 (Serial)"]
    end

    subgraph Providers["Providers - one pair per deck.hardware.mode"]
        VEND["real: Cfw11Drive / CellValueStreamAdapter<br/>drivers source set, lib/*.jar"]
        SIM["simulated: SimulatedDrive / SimulatedLoadCellStream<br/>shared SimulatedBench"]
    end

    subgraph DeviceLayer["device/ - singleton DeviceService"]
        LCD["LoadCellDevice<br/>(extends Device)<br/>readData() loop, 20ms"]
        CFD["CFW11Device<br/>(extends Device)<br/>readData() loop, 400ms"]
        FRS["FourWayRelaySwitch<br/>(plain object)"]
    end

    subgraph Broadcasters
        FB["ForceBroadcaster<br/>buffers ~60ms"]
        DIB["DeviceInfoBroadcaster"]
    end

    subgraph Topics["STOMP topics"]
        T1["/topic/load-cell"]
        T2["/topic/frequency-converter-info"]
    end

    DSC --> VEND
    CFW --> VEND
    REL -->|"RS232 115200 8N1"| FRS
    VEND --> LCD
    VEND --> CFD
    SIM -.-> LCD
    SIM -.-> CFD

    LCD -->|MeasurementObserver| FB --> T1
    CFD -->|InfoObserver| DIB --> T2
    LCD --> LCT["LoadCellThread<br/>(testrunner)"]
    LCT -.->|signals| TC[TestContext]
    LCT -.->|writes CSV| FS[(filesystem)]

    classDef topic fill:#fff3cd,stroke:#664d03
    class T1,T2 topic
```

Source: [`doc/diagrams/src/hardware-layer.mmd`](../diagrams/src/hardware-layer.mmd).

## Narrative

### The device API and its providers

No `src/main` class touches a vendor type. `ch.rupfizupfi.deck.device.api` declares
`Drive`, `LoadCellStream`, the deck's own `Measurement` / `StreamFailure` records and
a provider interface per device; the adapters (`…device.vendor`, optional `drivers`
source set) are the only code importing `ch.rupfizupfi.dscusb` / `.usbmodbus`.
Spring injects the providers into `DeviceService`, which passes them to both devices.

Providers are **factories, not singletons**: a stopped `LoadCellStream` can never be
restarted, and the safe-stop escalation opens a second drive handle mid-stop.
`HardwareModeCheck` decides at startup whether `deck.hardware.mode` can be served,
and never falls back. API shape, startup contract, Gradle wiring:
[`../06-feature-work/virtual-devices/driver-api-extraction.md`](../06-feature-work/virtual-devices/driver-api-extraction.md).

Each mode supplies its own provider pair, mutually exclusive by condition: the vendor
adapters (`drivers` source set) for `real`, and `…device.simulated` — one plant model
behind a fake drive and load cell — for `simulated`, the `dev` default. Plant model,
uncalibrated parameters:
[`../06-feature-work/virtual-devices/README.md`](../06-feature-work/virtual-devices/README.md).

### The `Device` base class

`Device` (`command-deck/src/main/java/ch/rupfizupfi/deck/device/Device.java:17`)
is a thin reference-counted lifecycle wrapper:

* `connect()` increments a counter; the *first* caller actually opens the
  connection (`openConnection()`).
* `disconnect()` decrements; the *last* caller actually closes it.
* `getConnectionStatus()` returns a `CompletableFuture<Boolean>` for awaiting it.

This lets multiple subsystems (a UI page, a test runner, the info
broadcaster) share one physical USB session without manually coordinating.
Both `LoadCellDevice` and `CFW11Device` extend it.
`FourWayRelaySwitch` does not — relay calls are short-lived and connect /
disconnect on each use.

### `DeviceService` — singleton coordinator

`DeviceService`
(`command-deck/.../device/DeviceService.java:13`) is `@Service @Scope("singleton")`.
On construction it builds `CFW11Device`, `LoadCellDevice` and
`DeviceInfoBroadcaster` — passing each device its injected provider — and registers
a `ForceBroadcaster` on the load cell so its data streams to `/topic/load-cell`.

`enableInfoBroadcasting()` is the entry point used by `DeviceInfoService`
(Hilla service called by the *Control* React view); it connects both devices
and registers the `DeviceInfoBroadcaster` to the frequency-converter.
`disableInfoBroadcasting()` reverses the steps — when the last view leaves,
USB sessions close.

### Load cell — `DSCUSB` over USB

* Driver: `lib/dscusb.jar`, tracked in git, reached only through
  `CellValueStreamAdapter` in the optional `drivers` source set. Absent, the
  build still succeeds but startup fails (no `LoadCellStreamProvider` bean).
  Its provenance, build requirements and the driver contract that decides run
  outcomes — a non-finite reading **ends the stream**, and a stopped stream can
  never be restarted — are in [`driver-jars.md`](driver-jars.md).
* Wrapper: `LoadCellDevice`
  (`command-deck/.../device/loadcell/LoadCellDevice.java:14`). `openConnection`
  asks its `LoadCellStreamProvider` for a **new** `LoadCellStream`, calls
  `startReading()`, spins a `dataThread` polling `stream.getNextValues()` every
  20 ms, and notifies registered `MeasurementObserver`s. `getStreamFailure()`
  turns `isReading()` + `lastError()` into the named cause the watchdog appends
  to a trip reason.
* Observer interface: `command-deck/.../device/loadcell/MeasurementObserver.java:7`
  — single `update(List<Measurement>)`.
* Broadcaster: `ForceBroadcaster`
  (`command-deck/.../device/loadcell/ForceBroadcaster.java:9`). Buffers
  measurements and flushes to `/topic/load-cell` only once the buffer's first
  sample is older than 60 ms — ~16 frames/s, to keep the WebSocket and React
  charts manageable.
* Test runner consumer: `LoadCellThread`
  (`command-deck/.../testrunner/LoadCellThread.java:15`) is *also* a
  `MeasurementObserver`. It writes every measurement to a CSV file and
  feeds force-threshold checks back to the test runner via `TestContext`
  signals — see [`test-types.md`](test-types.md).

### Frequency converter — `CFW11` over USB Modbus

* Driver: `lib/usbmodbus.jar`, reached only through `Cfw11Drive` in the same
  optional source set. Not in the repo, and its sibling build cannot currently
  run here — see [`driver-jars.md`](driver-jars.md).
* Wrapper: `CFW11Device`
  (`command-deck/.../device/frequencyconverter/CFW11Device.java:9`). Polls
  motor data + control parameters every 400 ms while at least one observer
  is registered. The `idProvider` field assigns a monotonic id to each
  `Info` snapshot so consumers can detect dropped frames.
* `Info` DTO — `command-deck/.../device/frequencyconverter/Info.java`: `id, speed,
  start, generalEnable, useSecondRamp, directionIsForward, motorCurrent,
  motorVoltage, motorTorque`.
* Broadcaster: `DeviceInfoBroadcaster`
  (`command-deck/.../device/frequencyconverter/DeviceInfoBroadcaster.java:5`)
  → `/topic/frequency-converter-info`.

The CFW11 is also used as an *actuator*: `AbstractTest` and its subclasses
(`DestructiveTest`, `CyclicTest`, `TimeCyclicTest`) drive it through
`MotorSafetyController.withDrive` / `queryDrive`, which hand out a `Drive` and hold
the drive lock for the call. They are the only doors — the `getHardwareComponent()`
accessor that returned the raw handle with no lock is gone from both devices and
from `Device`.

### 4-way relay — serial via `jSerialComm`

* Driver: `com.fazecast:jSerialComm:2.11.4` (declared in root `build.gradle`).
* Wrapper: `FourWayRelaySwitch`
  (`command-deck/.../device/relayswitch/FourWayRelaySwitch.java:5`). Auto-detects
  the COM port by scanning `SerialPort.getCommPorts()` for a descriptive name
  containing `CH9102` (the relay board's USB-serial chipset), throwing
  `ComportNotFoundException` if absent. 115 200 baud, 8N1.
* Commands: writes the ASCII byte `'0'` or `'1'` (`disableRelay1` /
  `enableRelay1`). Used by `SuckService` (manual) and `SuckJob` (post-
  destructive-test cleanup).

### Pre-test checks

The load cell is probed before a run: `LoadCellCheck`
(`command-deck/src/main/java/ch/rupfizupfi/deck/testrunner/startup/check/LoadCellCheck.java:16`)
opens the device and refuses the test unless a *fresh* measurement arrives within
2 s — `connect()` and `isConnected()` both return normally for a device that is not
plugged in, so an arrived measurement is the only trustworthy evidence. The CFW11
has no equivalent check (OQ-44). Mechanism and full check list:
[`test-execution-engine.md`](test-execution-engine.md#startup-checks).

### Topic summary

| Topic | Producer | Payload |
|---|---|---|
| `/topic/load-cell` | `ForceBroadcaster` | `List<Measurement>` (timestamp + force) |
| `/topic/frequency-converter-info` | `DeviceInfoBroadcaster` | `Info` snapshot |
| `/topic/logs` | `TestLogger.log` | `String` lines (also written to disk) |
| `/topic/status` | `TestRunnerService` (indirectly via TestLogger / future) | run state — endpoint declared in `WebSocketConfig` |

`/status` and `/logs` are registered as STOMP endpoints in
`cms/src/main/java/ch/rupfizupfi/deck/messaging/WebSocketConfig.java:21`; the broker is
`enableSimpleBroker("/topic")` — in-memory and single-instance, fine for one tester
appliance and not horizontally scalable.

## Where to look in the code

| Concern | File |
|---|---|
| Vendor-free device API | `command-deck/src/main/java/ch/rupfizupfi/deck/device/api/` |
| Vendor adapters (optional source set) | `command-deck/src/drivers/java/ch/rupfizupfi/deck/device/vendor/` |
| Simulated devices, plant model, fault switches | `command-deck/src/main/java/ch/rupfizupfi/deck/device/simulated/` |
| Startup mode enforcement | `command-deck/src/main/java/ch/rupfizupfi/deck/device/HardwareModeCheck.java` |
| Reference-counted base | `command-deck/src/main/java/ch/rupfizupfi/deck/device/Device.java:17` |
| Singleton coordinator | `command-deck/src/main/java/ch/rupfizupfi/deck/device/DeviceService.java:13` |
| Load cell driver wrapper | `command-deck/src/main/java/ch/rupfizupfi/deck/device/loadcell/LoadCellDevice.java:14` |
| Load cell broadcaster | `command-deck/src/main/java/ch/rupfizupfi/deck/device/loadcell/ForceBroadcaster.java:9` |
| Frequency converter wrapper | `command-deck/src/main/java/ch/rupfizupfi/deck/device/frequencyconverter/CFW11Device.java:9` |
| Freq converter broadcaster | `command-deck/src/main/java/ch/rupfizupfi/deck/device/frequencyconverter/DeviceInfoBroadcaster.java:5` |
| Relay switch | `command-deck/src/main/java/ch/rupfizupfi/deck/device/relayswitch/FourWayRelaySwitch.java:5` |
| Test runner load-cell consumer | `command-deck/src/main/java/ch/rupfizupfi/deck/testrunner/LoadCellThread.java:15` |
| WebSocket config | `cms/src/main/java/ch/rupfizupfi/deck/messaging/WebSocketConfig.java:11` |
| `drivers` source set wiring | `command-deck/build.gradle` |

## Open questions

Owned by [`OPEN-QUESTIONS.md`](../OPEN-QUESTIONS.md); listed here only so this
page names what it doesn't cover.

| OQ | Topic |
|---|---|
| OQ-44 | No presence check for the CFW11 — a missing converter surfaces only once `setup()` throws. `Cfw11Check` should follow `LoadCellCheck` |
| OQ-45 | Reconnect and resume after load-cell loss. Decided; the window length and how the result records the gap are unspecified |
| OQ-74 | One non-finite reading ends the stream, and therefore the run |
| OQ-46 | `FourWayRelaySwitch.java:19` matches the `CH9102` literal — move it to configuration |
| OQ-62 | The seam exists; the simulated providers do not, so no test can yet run without hardware. Decided: [simulated devices](../06-feature-work/virtual-devices/README.md), `dev` only |
| OQ-70 | `DeviceInfoService.isEnabled` is process-global, not per-client |
| OQ-43, OQ-75, OQ-76 | The two driver repos — see above |
