---
branch: dev-split
date: 2026-04-25
---

# Module layout: `:cms` vs `:command-deck`

## Purpose
Explain why this Gradle multi-project repo ships **two Spring Boot applications** that share a single Java root package (`ch.rupfizupfi.deck`), what each module owns, and how data and code flow between them. Detailed Spring scanning behaviour is in [`spring-boot-setup.md`](spring-boot-setup.md); cross-consumed packages are catalogued in [`shared-code-strategy.md`](shared-code-strategy.md).

## Diagram

```mermaid
graph LR
    subgraph repo["breaktest-command-deck (Gradle root)"]
        cd[":command-deck<br/>command-deck-application.jar"]
        cms[":cms<br/>cms-application.jar"]
    end

    cd -->|implementation project(':cms')| cms
    cd -->|optional 'drivers' source set| dscusb["lib/dscusb.jar<br/>lib/usbmodbus.jar"]

    cms --> vaadin["Vaadin Hilla 25.2.6"]
    cms --> sb["Spring Boot 4.1.0<br/>(security, data-jpa, websocket,<br/>validation, aspectj, devtools)"]
    cms --> db["H2 + PostgreSQL"]
    cms --> misc["jSerialComm, POI,<br/>Line Awesome, Logback"]
```

Source diagram: [`doc/diagrams/src/module-graph.mmd`](../diagrams/src/module-graph.mmd).

## Narrative

### Why two Spring Boot apps?
The repo ships two `@SpringBootApplication` classes, both in package `ch.rupfizupfi.deck`:

- `cms/src/main/java/ch/rupfizupfi/deck/Application.java:24` — Content-Management deployment. Owns every JPA entity (10 of them), all `@RestController`s under `/api/**`, all 11 customer/sample/project/result `@BrowserCallable` Hilla services, the `SecurityConfiguration`, the `WebSocketConfig` and the `data.sql` seed.
- `command-deck/src/main/java/ch/rupfizupfi/deck/Application.java:23` — Hardware-control deployment. Adds the test-execution engine (`testrunner/*`), the device drivers (`device/loadcell`, `device/frequencyconverter`, `device/relayswitch`) and three more `@BrowserCallable` services (`SuckService`, `TestRunnerService`, `DeviceInfoService`).

The split is a **deployment** boundary, not a compile-time isolation: the same H2 file (`./.data/deck` in dev) and the same PostgreSQL schema in `docker` are read/written by both apps. The CMS image runs anywhere there is a database; the command-deck image runs only on the physical bench computer that has the USB load cell, the CFW11 frequency converter and the relay switch wired up. Putting hardware code in a separate fat JAR keeps `dscusb.jar` (a native USB driver) off the CMS classpath, where it would be useless.

A side-effect — and a quirk worth knowing — is that **`command-deck` is a strict superset of `cms` at runtime**: it depends on `cms` (`command-deck/build.gradle:2`), inherits its component scan, ships every Hilla service `cms` exposes, and merges the cms frontend routes into its own bundle (`command-deck/customFileSystemRouterPlugin.ts:73`). So `:command-deck:bootRun` serves the CMS UI **plus** the run/control views; `:cms:bootRun` only serves the CMS UI.

### What each module owns

| Concern | `:cms` | `:command-deck` |
|---|---|---|
| `@SpringBootApplication` | yes (`Application.java:24`) | yes (`Application.java:23`) |
| JPA `@Entity` classes | **all 10** (listed in [`../01-overview/product-context.md`](../01-overview/product-context.md); shape in [`../03-backend/persistence-model.md`](../03-backend/persistence-model.md)) | none |
| Spring Data repositories (`UserRepository`, `TestResultRepository`, etc.) | yes, in `data/` | none |
| `@RestController` (mapped to `/api/**`) | 3 | 0 |
| `@BrowserCallable` (Hilla RPC) | 11 | 3 |
| `@Configuration` beans | `SecurityConfiguration`, `WebSocketConfig` | none |
| File-system / CSV services | `filesystem/StorageLocationService`, `filesystem/CSVStoreService` | none |
| Hardware drivers | none | `device/*` |
| Test execution engine | none | `testrunner/*` |
| `data.sql` seed | yes | no file of its own, but it inherits cms's via the `cms-library` jar, so a deck-only boot against an empty DB **does** seed |

### Shared package root: `ch.rupfizupfi.deck`
Both modules root their classes under the same package. Concretely, `ch.rupfizupfi.deck` and `ch.rupfizupfi.deck.api.services` exist in **both** modules' source trees:

- `cms/src/main/java/ch/rupfizupfi/deck/Application.java`
- `command-deck/src/main/java/ch/rupfizupfi/deck/Application.java`
- `cms/src/main/java/ch/rupfizupfi/deck/api/services/UserService.java`
- `command-deck/src/main/java/ch/rupfizupfi/deck/api/services/TestRunnerService.java`

This is legal — Java only requires that no two classes share a fully-qualified name — and is what makes Spring's default component scan from `ch.rupfizupfi.deck` (the package of the `@SpringBootApplication`) find every `cms` bean automatically when `:command-deck` boots. See [`spring-boot-setup.md`](spring-boot-setup.md) for the scan-mechanics walkthrough.

### Build outputs
A clean `./gradlew build` produces, per module, both a Spring Boot fat JAR (`bootJar`) and a plain library JAR. Both are explicitly enabled in the per-module build files:

- `cms/build.gradle:1` → `cms-application.jar` (boot) + `cms-library-plain.jar` (plain)
- `command-deck/build.gradle:10` → `command-deck-application.jar` (boot) + `command-deck-library-plain.jar` (plain)

The plain JARs are byproducts. The Dockerfiles `COPY .../build/libs/*.jar /app/MODULE.jar`, and the glob is safe because each image runs `gradle clean :MODULE:bootJar` — `bootJar` does not depend on `jar`, so `build/libs/` holds exactly one artefact at `COPY` time. Changing that build command to `assemble` or `build` would put two jars there and break the image.

`cms-library.jar` exists solely so `project(':cms')` resolves for `:command-deck`; no external consumer is known. See [`shared-code-strategy.md`](shared-code-strategy.md).

### Frontend per module
Each module has its own `src/main/frontend/`, its own `vite.config.ts`, its own `package.json`, its own Vaadin `generated/` tree, and ships its own React bundle. They are not merged at the JS level, but `:command-deck`'s Vite config aliases `cms` to `../cms/src/main/frontend` (see `command-deck/vite.config.ts:30`) and a custom plugin merges the cms `file-routes.json` into the command-deck one at build start (`command-deck/customFileSystemRouterPlugin.ts:73`). Build-time integration is documented in [`gradle-build.md`](gradle-build.md); behavioural / runtime details belong to [`../04-frontend/`](../04-frontend/) and are out of scope here.

## Where to look in the code
- `settings.gradle` — declares the two Gradle subprojects (no third module).
- `build.gradle` (root) — `subprojects { ... }` block applies plugins and dependencies to **both** modules.
- `cms/build.gradle:1`, `command-deck/build.gradle:1` — per-module overrides; `command-deck` adds `implementation project(':cms')`.
- `cms/src/main/java/ch/rupfizupfi/deck/Application.java` — CMS entry point, `@Theme("breaktest-command-deck")` + `@ColorScheme(DARK)`, custom `ApplicationDataSourceScriptDatabaseInitializer` that only seeds when `UserRepository.count() == 0`.
- `command-deck/src/main/java/ch/rupfizupfi/deck/Application.java` — command-deck entry point, identical shape. **Same fully-qualified name as the cms class**, so only one of them ever loads; see [`spring-boot-setup.md`](spring-boot-setup.md).
- `command-deck/customFileSystemRouterPlugin.ts:73` — frontend route merge step.
- Annotation-by-annotation breakdowns: `@BrowserCallable` and `@RestController` in [`../03-backend/hilla-services.md`](../03-backend/hilla-services.md), `@Entity` in [`../03-backend/persistence-model.md`](../03-backend/persistence-model.md), `@Configuration` / `@Aspect` in [`../03-backend/security-and-tenancy.md`](../03-backend/security-and-tenancy.md).

## Open questions

1. **Dedupe the `application*.properties` files.** Decided: yes. `application.properties` and `application-dev.properties` are byte-identical between modules and `application-docker.properties` differs only in line endings, so the cms classpath copies become canonical and the deck copies go away. Confirm classpath ordering for the profile-specific files before deleting. (OQ-4)
