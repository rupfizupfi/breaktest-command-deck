# Breaktest Command Deck — Product Description

## What it is

A web-based control system for an electric motor-driven material testing machine. The machine performs two classes of tests on textile, rope, and cable specimens (slings, shackles, carabiners, etc.):

- **Destructive tests** — apply increasing force until the specimen fails
- **Cyclic tests** — cycle force between a lower and upper threshold N times, characterizing fatigue behavior

The system replaces manual machine operation with a browser-based interface that records all measurements, manages test metadata (projects, samples, customers, materials, gear standards), and exports results to CSV/Excel.

---

## Hardware

| Component | Part | Interface |
|-----------|------|-----------|
| Motor driver | CFW11 frequency converter | USB Modbus (`dscusb.jar`) |
| Force sensor | Load cell | USB (`CellValueStream`) |
| Suction pump | 4-way relay switch (CH9102 USB-UART) | Serial, 115200 8N1 |
| Optional camera | Webcam | Browser MediaDevices API |

---

## Motor Control (CFW11 Frequency Converter)

### Connection
The CFW11 is connected over USB Modbus. `CFW11Device` wraps the third-party `Cfw11` library and manages a reference-counted connection so multiple test components can share the device handle.

### Commands used during a test

| Operation | API call | Notes |
|-----------|----------|-------|
| Enable motor | `cfw11.setGeneralEnable(true)` | Must be set before start |
| Start rotation | `cfw11.setStart(true)` | |
| Set speed | `cfw11.setSpeedReferenceValueAsRpm(rpm)` | Speed in Hz is converted: `rpm = hz / 0.375` |
| Set direction | `cfw11.setDirection(bool)` | `true` = forward/release, `false` = backward/pull |
| Second ramp | `cfw11.setUseSecondRamp()` + `cfw11.setSecondSpeedRampTime(accel, decel)` | Ramp times in units of 100 ms |
| Communication error action | `cfw11.setActionInCaseOfCommunicationError(2)` | Value 2 = disable via general enable on USB loss |

### Speed ramp (cyclic tests)
During the analysis phase a cyclic test runs at a fixed low speed (50 Hz → ~133 RPM) and measures the real travel time for pull and release strokes. In the execution phase it scales those times to the target speed and programs the second-ramp acceleration/deceleration times accordingly, so direction changes are smooth rather than abrupt.

### Telemetry
`DeviceInfoBroadcaster` polls the converter every **400 ms** and publishes a JSON snapshot — speed (RPM), current (A), voltage (V), torque (N·m), and all control flags — to the WebSocket topic `/topic/frequency-converter-info`. The frontend subscribes and renders live gauges.

### Emergency shutdown path
If the test thread throws an unhandled exception, `TestRunnerThread.retryShutdownOnException()` opens a **fresh** `Cfw11` connection (the existing one may be corrupted) and forces: `generalEnable = false`, `speed = 0 RPM`, `start = false`, then closes the USB connection. This ensures the motor stops even when the normal cleanup path fails.

---

## Load Cell (Force Measurement)

`LoadCellDevice` opens a `CellValueStream` over USB and spawns a background thread that reads measurements every **20 ms**. Each `Measurement` carries a millisecond timestamp and a force value in Newtons.

During a test, `LoadCellThread` accumulates readings and:
- Writes every sample to a CSV file (`timestamp,force`)
- Tracks running min/max
- Posts a `RELEASE_SIGNAL` to the test's signal queue when force exceeds the **upper limit**
- Posts a `PULL_SIGNAL` when force drops below the **lower limit**

`ForceBroadcaster` batches readings and emits them to `/topic/load-cell` every **60 ms** for live charting in the browser.

---

## Test Types

### Destructive test
Motor drives in one direction until the specimen breaks (signal queue receives no further threshold crossings or the operator stops). After at least 2 seconds of runtime the suction pump fires automatically (via `SuckJob`) to retrieve the broken specimen end.

### Cyclic test
Motor alternates direction every time force crosses a threshold:
- PULL_SIGNAL → switch to release direction
- RELEASE_SIGNAL → switch to pull direction

After each full cycle the remaining cycle counter decrements and the thresholds are adjusted to the actual min/max values measured in the previous cycle. Test ends when the counter reaches zero.

### Time-cyclic test
Same as cyclic but the direction-change timing is derived from the analysis phase (measured travel times) rather than force thresholds. `TimeProcessor` schedules signals at the calculated intervals so the motor reverses at precise time points regardless of force readings.

---

## Suction Pump (Relay Switch)

`FourWayRelaySwitch` opens the first COM port whose description contains `"CH9102"` at 115200 baud. Relay 1 is toggled by sending the ASCII character `"1"` (enable) or `"0"` (disable) as raw bytes. `SuckService` (a Hilla endpoint) lets the operator activate it manually; `SuckJob` activates it automatically post-destructive-test for a configurable duration.

---

## Webcam Distance Measurement (Optional)

`DistanceMeasureCam` streams a 640×480 webcam feed. The operator calibrates by clicking two points and entering the real-world distance, establishing a px/cm scale.

`CamShiftTracking` tracks a user-selected region of interest frame-by-frame using OpenCV.js (CAMShift algorithm on HSV back-projection with Gaussian blur + morphological opening). It reports the displacement of the tracked object in cm, providing non-contact elongation measurement of the specimen during testing.

---

## Data & Results

- Every test writes a raw CSV file to `./.data/deck` (dev) or a mounted volume (docker)
- Test metadata (project, sample, parameters, peak force, cycle count) is stored in the database
- Results are downloadable as CSV (all results) or Excel workbook (per project) via `/api/DownloadEndpoint`

---

## Security Architecture

### Authentication
Spring Security with BCrypt password hashing. The login view is `/login`; Vaadin's `VaadinWebSecurity` enforces authentication on all framework-managed routes.

### Authorisation layers

| Layer | Mechanism | Detail |
|-------|-----------|--------|
| View access | Vaadin session | All views require login |
| Data ownership | AOP aspect `@CheckUserCanOnlyAccessOwnData` | Fetches entity from DB, compares `getOwner()` to current user; throws `SecurityException` on mismatch |
| Admin override | Role `ROLE_ADMIN` | Bypasses ownership check entirely |
| Hardware services | `@PermitAll` | Any authenticated user can start/stop tests and control the motor |

### Known access-control gaps

**Any authenticated user can operate the hardware.** `TestRunnerService`, `DeviceInfoService`, and `SuckService` are all marked `@PermitAll`. There is no `ROLE_OPERATOR` or similar guard in front of motor start, direction change, or relay commands. In the current deployment this is acceptable because the application is only accessible inside the lab network, but it should be addressed if the system is ever exposed more broadly.

**REST file endpoints are unauthenticated.** `/api/files/upload`, `/api/files/uploads`, and `/api/files/image/{fileName}` carry `@AnonymousAllowed` and have CSRF disabled. File-type validation is not enforced in the endpoint code.

**WebSocket topics are not per-message authenticated.** Subscriptions to `/topic/load-cell` and `/topic/frequency-converter-info` rely on the HTTP session that was authenticated when the WebSocket was upgraded; there is no token check on individual STOMP frames.

### Transport security
Production (docker profile) runs on port 443 with a PKCS12 keystore (`${KEY_STORE_PASSWORD}` from environment). Development runs plain HTTP on 8080. Database credentials come from `${DB_PASSWORD}` in production; in development the H2 file database has an empty password.

### Hardware-specific risk
Because the motor can reverse direction and there is no software-enforced deceleration delay between a stop and a direction change command, a malicious or buggy client call sequence could cause abrupt mechanical stress. The only protection is the converter's own ramp settings — which are configurable at test setup time and can be set to zero.

---

## Deployment

Two independently deployable Spring Boot applications share a PostgreSQL database (docker) or H2 file database (dev):

- **cms** — web UI and API for managing projects, samples, customers, standards, and results. Port 8043 externally.
- **command-deck** — web UI for running tests and operating the hardware. Port 8043 externally (separate container).

Both are built with `gradle clean :<module>:bootJar -Pvaadin.productionMode=true` and run as a non-root user (`appuser`, UID 10001) inside eclipse-temurin:23-jre containers.
