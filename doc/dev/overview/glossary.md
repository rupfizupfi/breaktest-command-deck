# Glossary

Cross-cutting terms used throughout the docs and the code. Detail-pages are
linked where each concept is documented in depth.

| Term | Definition | Documented in |
|---|---|---|
| **`@BrowserCallable`** | Vaadin Hilla annotation that exposes a Java service method to the frontend as a typed RPC. Generates a TypeScript client into `src/main/frontend/generated/`. | [`dev/backend/hilla-services.md`](../backend/hilla-services.md), [`dev/frontend/hilla-generated-layer.md`](../frontend/hilla-generated-layer.md) |
| **CFW11** | WEG CFW11 series industrial frequency inverter (variable-frequency drive) that drives the test bench's electric motor. Spoken to over USB Modbus via `usbmodbus.jar`. | [`dev/backend/hardware-integration.md`](../backend/hardware-integration.md) |
| **CMS app** | The `:cms` Spring Boot module: projects/samples/customers/results CRUD, REST under `/api/**`, all JPA entities and seed data. Deploys as `cms-application.jar`. | [`dev/modules/module-layout.md`](../modules/module-layout.md) |
| **Command-deck app** | The `:command-deck` Spring Boot module: a runtime superset of cms that adds hardware drivers (`device/*`) and the test-execution engine (`testrunner/*`). Deploys as `command-deck-application.jar`. | [`dev/modules/module-layout.md`](../modules/module-layout.md) |
| **Cyclic test** | Repeatedly load and unload a sample for a configured number of cycles. Implemented by `CyclicProgram`. | [`dev/backend/test-types.md`](../backend/test-types.md) |
| **`DataWithOwner`** | Marker interface on every owner-scoped JPA entity. The `@CheckUserCanOnlyAccessOwnData` aspect uses it to scope queries to the current user. | [`dev/backend/security-and-tenancy.md`](../backend/security-and-tenancy.md) |
| **Destructive test** | Pull a sample until it breaks; record force curve and breaking load. Implemented by `DestructiveProgram`. | [`dev/backend/test-types.md`](../backend/test-types.md) |
| **DSCUSB** | Mantracourt DSCUSB strain-gauge interface — a USB-attached load-cell amplifier. Driven by the `dscusb` plugin jar, loaded at runtime by `:command-deck` only. | [`dev/backend/hardware-integration.md`](../backend/hardware-integration.md) |
| **Hilla** | Vaadin's full-stack framework (25.2.6 here). Generates TypeScript clients from Java `@BrowserCallable` services and bridges Spring Security to the React side. | [`dev/frontend/hilla-generated-layer.md`](../frontend/hilla-generated-layer.md) |
| **Load cell** | Force-measurement transducer. Here, the DSCUSB device wired to the test bench. | [`dev/backend/hardware-integration.md`](../backend/hardware-integration.md) |
| **`MeasurementLoop`** | The force watchdog of a run (`testrunner.measure`, `LoadCellThread` before OQ-64 step 10): consumes the load cell's batches, runs the four detectors, emits limit crossings to the `RunLoop` and hands every batch to the `TraceWriter` that owns the CSV. | [`dev/backend/test-types.md`](../backend/test-types.md) |
| **`RunLoop`** | One run's control thread: consumes `RunEvent`s (crossings, timed turns, Stop, Abort, trips) and runs the run's entry actions; a program ends a run by answering `Verdict.finish`. | [`dev/backend/test-execution-engine.md`](../backend/test-execution-engine.md) |
| **Relay switch** | Four-way relay (CH9102 chipset) controlled over RS232 via `jSerialComm`. Used for motor direction / safety cut-off. | [`dev/backend/hardware-integration.md`](../backend/hardware-integration.md) |
| **STOMP** | Simple Text-Oriented Messaging Protocol; here used over WebSocket for live force/info push to the React UI. Topics: `/topic/load-cell`, `/topic/frequency-inverter-info`, `/topic/status`, `/topic/logs`. | [`dev/frontend/state-and-realtime.md`](../frontend/state-and-realtime.md) |
| **`TestProgram`** | What a test type declares — limits, energize `DriveSetup`, `ResumePolicy`, verdicts on crossings; one per `TestType` in the `TestProgramRegistry`. | [`dev/backend/test-types.md`](../backend/test-types.md) |
| **`TestRunnerThread`** | Not a thread itself: owns one run's thread, state machine and operator commands (Stop, Abort, Resume); the thread runs the `RunLoop`. | [`dev/backend/test-execution-engine.md`](../backend/test-execution-engine.md) |
| **Time-cyclic test** | Cyclic variant that times its release and pull legs in a calibration phase, then reverses on those times rather than on the limits. Implemented by `TimeCyclicProgram` (`CalibrationPhase` → `TimedCyclePhase`). | [`dev/backend/test-types.md`](../backend/test-types.md) |
