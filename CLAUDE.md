# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

**Breaktest Command Deck** is a full-stack control system for an electric motor-based material testing apparatus (destructive and cyclic load testing). Built with **Vaadin Hilla** (Spring Boot + React/TypeScript with auto-generated type-safe RPC between layers).

Reference documentation lives in `doc/` — start at `doc/README.md`. `doc/_research/` carries one caveat, and it is about **adoption, not subject matter**: nothing it evaluates (test harnesses, device simulators) exists in this repo, so treat every tool it names as absent unless you verify otherwise in the build files. The agent tooling described above is the one part that shipped.

It does contain verified findings about *this* code, and the reference docs cite them as authority — the device transport table and CFW11 register map decompiled from `lib/*.jar`, `Cfw11` being `final`, the empty `src/test` trees, the drive-side watchdog parameter `P0314` never being set. Don't discount a `_research/` finding just because nothing in that folder is built.

## Build & Run Commands

```bash
# Build entire project
./gradlew build

# Run (dev profile with H2, port 8080). One at a time — both open the
# same H2 file exclusively. Either seeds it; data.sql reaches deck via the cms jar.
./gradlew :command-deck:bootRun
./gradlew :cms:bootRun

# Production JARs
./gradlew :command-deck:bootJar -Pvaadin.productionMode=true
./gradlew :cms:bootJar -Pvaadin.productionMode=true
```

There are no tests in this codebase. Two verification gates exist instead:

```powershell
# Cross-language contract check: Hilla generates the TS client from Java bytecode,
# so a backend signature change surfaces as a frontend type error. ~11s warm.
./script/typecheck.ps1                 # regenerates the Hilla client, then tsc --noEmit
./script/typecheck.ps1 -SkipGenerate   # ~8s, when generated/ is already current
```

`/deck-run` is the other: it boots the app, screenshots every view and runs a CRUD smoke test, returning PASS/FAIL. It runs in a forked subagent, so it reports a verdict rather than filling the conversation.

For per-file Java/TS diagnostics, connect the IDE: install the **Claude Code** plugin in IntelliJ, then run **`/ide`** with the project open. Claude then sees the IDE's compile and inspection errors for each file it edits. This needs IntelliJ running and indexed, and its file watcher can lag a second or two behind an edit — so it complements `typecheck.ps1` rather than replacing it. IDE diagnostics are per-file; only the typecheck gate catches a Java signature change breaking a *different* file's generated client.

## Automated guardrails

`.claude/settings.json` wires two hooks. They are enforcement, not advice — expect them to block:

- **`guard-protected-paths.mjs`** (PreToolUse) denies edits to both modules' `frontend/generated/` trees, any `vite.generated.ts`, `.claude/skills/deck-run/driver.mjs` and its `shots/` baselines, and `.claude/hooks/` itself. Generated files are overwritten by the build; the driver and its baselines are the repo's only verification harness, and a grader must not be editable by the code it grades. To change a guarded file, edit it by hand outside Claude Code.
- **`check-jackson-imports.mjs`** (PostToolUse) flags `com.fasterxml.jackson.databind.*` and `.core.*` imports in `.java` files and names the `tools.jackson.*` replacement. It deliberately never flags `com.fasterxml.jackson.annotation.*`, which is shared between Jackson 2 and 3 and is correct — do not "fix" those.

## Docker Deployment

The two profiles are **two deployments on two hosts**, not two roles for one machine: `cms` runs on a cloud host, `command-deck` runs on the machine physically wired to the test bench (it needs local USB/serial access). Both share host port 8043 precisely because they never coexist. The deck is meant to use the cloud database — see `doc/05-ops/docker-and-profiles.md`; the config still points at a Compose-local Postgres (OQ-61).

```bash
# On the cloud host
docker compose -f docker/docker-compose.yaml --profile cms up -d
# On the tester
docker compose -f docker/docker-compose.yaml --profile deck up -d
```

Docker uses two-stage builds (gradle:9.7.0-jdk26-corretto → eclipse-temurin:26-jre). External port 8043 maps to internal 443. Requires `docker/.secrets/db-password.txt`; `docker/keystore/` holds the PKCS12 cert, and `startup.sh` self-signs one if absent — that is the intended operating mode, not a fallback.

## Module Structure

Two Spring Boot modules sharing the same Spring Security / JPA setup:

- **cms/** — Content management: projects, samples, test parameters, customers, materials, gear types/standards, test results. Has REST endpoints under `/api/**` (excluded from Vaadin routing) and Hilla `@BrowserCallable` services.
- **command-deck/** — Hardware control and test execution. Depends on the `cms` module. Handles serial device communication, real-time WebSocket streaming, and test lifecycle management.

Both modules share the package root `ch.rupfizupfi.deck` and the same H2 database in dev.

## Technology Stack

- **Java 26**, Spring Boot 4.1 (Spring Framework 7, Jakarta EE 11), Spring Data JPA, Spring Security, Spring WebSocket
- **Vaadin 25.2.6 / Hilla** — `@BrowserCallable` Java services generate TypeScript clients into `src/main/frontend/generated/`. Vaadin 25 no longer bundles Hilla in `vaadin-spring-boot-starter`, so `com.vaadin:hilla-spring-boot-starter` is declared explicitly.
- **Jackson 3** (`tools.jackson.*`) — Spring Boot 4's default mapper. Note `@Json*` annotations still come from `com.fasterxml.jackson.annotation` (jackson-annotations is shared between Jackson 2 and 3), but databind types such as `@JsonSerialize`, `ValueSerializer`, and `SerializationContext` are under `tools.jackson.databind`.
- **React 19** + TypeScript 6, Vite 8, React Router 7
- **Gradle 9.7** (wrapper)
- **jSerialComm** — Serial port communication with hardware devices
- **PostgreSQL** (docker/prod), **H2** (dev)

## Architecture Patterns

### Vaadin Hilla RPC
Java services annotated `@BrowserCallable` (and optionally `@AnonymousAllowed` / `@RolesAllowed`) are automatically exposed to the frontend. Auto-generated TypeScript clients live in `src/main/frontend/generated/`. **Never edit generated files.**

### Frontend Structure
```
src/main/frontend/
├── views/          # File-based routing (@layout.tsx, @index.tsx, etc.)
├── components/     # Reusable React components
├── service/        # Frontend-side service helpers
├── model/          # Data model utilities
├── generated/      # Auto-generated by Vaadin (DO NOT EDIT)
└── themes/         # Custom CSS
```

### Hardware Communication (command-deck)
- `device/loadcell/` — Load cell force measurement via serial (ForceBroadcaster → WebSocket)
- `device/frequencyconverter/` — CFW11 frequency converter control (DeviceInfoBroadcaster)
- `device/relayswitch/` — Four-way relay control
- Real-time data pushed to frontend via STOMP over WebSocket (`@stomp/rx-stomp` on frontend)

### Test Execution Engine (command-deck)
- `TestRunnerFactory` creates typed test instances: `DestructiveTest`, `CyclicTest`, `TimeCyclicTest`
- `TestRunnerThread` manages lifecycle; `LoadCellThread` collects measurements; `SignalListener` monitors hardware signals
- Tests extend `AbstractTest`; throw `FinishTestException` to terminate cleanly
- Pre-run validation via `startup/check/AbstractCheck` implementations

### Security & Multi-tenancy
- Spring Security with custom `UserDetailsService`
- `@CheckUserCanOnlyAccessOwnData` — AOP aspect enforcing ownership on all `DataWithOwner` entities
- Users have roles `USER` and/or `ADMIN`
- Data initialization via `cms/src/main/resources/data.sql` (only runs when DB is empty)

### CrudRepositoryService Pattern
Most services extend `CrudRepositoryService<Entity, ID, Repository>` which provides standard CRUD exposed via Hilla. Custom business logic is added via override.

## Spring Profiles

| Profile | Database | Port | Notes |
|---------|----------|------|-------|
| `dev` | H2 file at `./.data/deck` | 8080 | H2 console at `/h2-console`, dev tools on |
| `docker` | PostgreSQL `db:5432/rupfizupfi` | 443 | HTTPS with PKCS12 keystore, passwords from env |

Set active profile via `SPRING_PROFILES_ACTIVE` env var. The `command-deck` module's `application-dev.properties` shares the same H2 DB path as CMS.

## Key Dependency: Local JARs
`lib/dscusb.jar` (load cell) and `lib/usbmodbus.jar` (CFW11 frequency converter) are optional local dependencies for USB device communication.

**A fresh clone compiles without either jar.** No `src/main` code imports `ch.rupfizupfi.dscusb` or `ch.rupfizupfi.usbmodbus`; the deck talks to `ch.rupfizupfi.deck.device.api` (`Drive`, `LoadCellStream`, and their providers) and the vendor adapters live in the optional `drivers` source set (`command-deck/src/drivers/java`), registered only when **both** jars are in `lib/`.

**Running is a different matter.** `deck.hardware.mode=real` (the default everywhere) needs both provider beans, so with the jars absent `HardwareModeCheck` fails startup naming the missing jars — it never falls back to a simulator. `mode=simulated` is also refused until simulated providers exist.

**`lib/usbmodbus.jar` is gitignored and cannot be committed** — its licence forbids redistribution, and the procurement source is still undocumented (OQ-43, owner-owed).

Both jars are built from sibling repos outside this one. `doc/03-backend/driver-jars.md` owns their provenance, build requirements and the driver contracts that decide run outcomes — read it before touching load-cell or drive code. Two things it records that bite: a non-finite load-cell reading **ends the stream and therefore the run** (OQ-74), and the `usbmodbus` repo cannot currently be rebuilt on the installed JDK (OQ-76).
