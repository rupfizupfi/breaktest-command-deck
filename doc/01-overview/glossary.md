> Branch: `dev-split` — captured 2026-04-25.

# Glossary

Cross-cutting terms used throughout the docs and the code. Detail-pages are
linked where each concept is documented in depth.

| Term | Definition | Documented in |
|---|---|---|
| **`@BrowserCallable`** | Vaadin Hilla annotation that exposes a Java service method to the frontend as a typed RPC. Generates a TypeScript client into `src/main/frontend/generated/`. | [`03-backend/hilla-services.md`](../03-backend/hilla-services.md), [`04-frontend/hilla-generated-layer.md`](../04-frontend/hilla-generated-layer.md) |
| **CFW11** | WEG CFW11 series industrial frequency converter (variable-frequency drive) that drives the test bench's electric motor. Spoken to over USB Modbus via `usbmodbus.jar`. | [`03-backend/hardware-integration.md`](../03-backend/hardware-integration.md) |
| **CMS app** | The `:cms` Spring Boot module: projects/samples/customers/results CRUD, REST under `/api/**`, all JPA entities and seed data. Deploys as `cms-application.jar`. | [`02-modules/module-layout.md`](../02-modules/module-layout.md) |
| **Command-deck app** | The `:command-deck` Spring Boot module: a runtime superset of cms that adds hardware drivers (`device/*`) and the test-execution engine (`testrunner/*`). Deploys as `command-deck-application.jar`. | [`02-modules/module-layout.md`](../02-modules/module-layout.md) |
| **Cyclic test** | Repeatedly load and unload a sample for a configured number of cycles. Implemented by `CyclicTest`. | [`03-backend/test-types.md`](../03-backend/test-types.md) |
| **`DataWithOwner`** | Marker interface on every owner-scoped JPA entity. The `@CheckUserCanOnlyAccessOwnData` aspect uses it to scope queries to the current user. | [`03-backend/security-and-tenancy.md`](../03-backend/security-and-tenancy.md) |
| **Destructive test** | Pull a sample until it breaks; record force curve and breaking load. Implemented by `DestructiveTest`. | [`03-backend/test-types.md`](../03-backend/test-types.md) |
| **DSCUSB** | Mantracourt DSCUSB strain-gauge interface — a USB-attached load-cell amplifier. Vendored as `lib/dscusb.jar`; only loaded by `:command-deck`. | [`03-backend/hardware-integration.md`](../03-backend/hardware-integration.md) |
| **`FinishTestException`** | Thrown by an `AbstractTest` subclass to terminate the run cleanly (cooperative shutdown). Caught by `TestRunnerThread`. | [`03-backend/test-types.md`](../03-backend/test-types.md) |
| **Hilla** | Vaadin's full-stack framework (25.2.6 here). Generates TypeScript clients from Java `@BrowserCallable` services and bridges Spring Security to the React side. | [`04-frontend/hilla-generated-layer.md`](../04-frontend/hilla-generated-layer.md) |
| **Load cell** | Force-measurement transducer. Here, the DSCUSB device wired to the test bench. | [`03-backend/hardware-integration.md`](../03-backend/hardware-integration.md) |
| **`LoadCellThread`** | Worker thread that consumes force samples from the load cell during a test run, writes them to CSV, and forwards values to the broadcaster. | [`03-backend/test-types.md`](../03-backend/test-types.md) |
| **Relay switch** | Four-way relay (CH9102 chipset) controlled over RS232 via `jSerialComm`. Used for motor direction / safety cut-off. | [`03-backend/hardware-integration.md`](../03-backend/hardware-integration.md) |
| **STOMP** | Simple Text-Oriented Messaging Protocol; here used over WebSocket for live force/info push to the React UI. Topics: `/topic/load-cell`, `/topic/frequency-converter-info`, `/topic/status`, `/topic/logs`. | [`04-frontend/state-and-realtime.md`](../04-frontend/state-and-realtime.md) |
| **`TestRunnerThread`** | The thread that owns one test execution: instantiates the typed `AbstractTest`, runs setup/check/loop/cleanup, signals completion. | [`03-backend/test-execution-engine.md`](../03-backend/test-execution-engine.md) |
| **Time-cyclic test** | Cyclic variant whose stop condition is elapsed time, not cycle count. Implemented by `TimeCyclicTest`. | [`03-backend/test-types.md`](../03-backend/test-types.md) |
