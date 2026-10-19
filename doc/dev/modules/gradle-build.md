# Gradle build

## Purpose
Document how the Gradle multi-project build wires `:cms` and `:command-deck`, where the Vaadin / Hilla plugin plugs in, and how the `drivers/*.jar` plugins reach the command-deck classpath. Spring-side configuration sits in [`spring-boot-setup.md`](spring-boot-setup.md); cross-module Java imports are listed in [`shared-code-strategy.md`](shared-code-strategy.md).

## Contents

- [Diagram](#diagram)
- [Narrative](#narrative)
  - [`settings.gradle`](#settingsgradle)
  - [Root `build.gradle`](#root-buildgradle)
  - [`:cms/build.gradle` (cms-specific)](#cmsbuildgradle-cms-specific)
  - [`:command-deck/build.gradle`](#command-deckbuildgradle)
  - [Driver plugins: `loader.path`, not the classpath](#driver-plugins-loaderpath-not-the-classpath)
  - [Vaadin Gradle plugin](#vaadin-gradle-plugin)
  - [The driver plugin directory (`drivers/`)](#the-driver-plugin-directory-drivers)
  - [Build outputs](#build-outputs)
  - [`gradle.properties`](#gradleproperties)
- [Continuous integration](#continuous-integration)
- [Where to look in the code](#where-to-look-in-the-code)

## Diagram

```mermaid
flowchart TD
    src["Source<br/>(Java + frontend/)"]

    subgraph gradle_phase["Gradle (per module)"]
        compile["compileJava<br/>JDK 26 toolchain"]
        hilla_gen["Hilla endpoint generation<br/>(com.vaadin Gradle plugin)<br/>scans @BrowserCallable<br/>writes generated/*.ts"]
        vite["Vite 8 bundle<br/>(vaadinPrepareFrontend +<br/>vaadinBuildFrontend)"]
        bootJar["bootJar<br/>cms-application.jar OR<br/>command-deck-application.jar"]
    end

    src --> compile --> hilla_gen --> vite --> bootJar

    subgraph cd_extra["command-deck only"]
        merge["customFileSystemRouterPlugin<br/>merges cms file-routes.json"]
    end
    vite -.-> merge -.-> bootJar

    bootJar --> docker["Docker stage 1<br/>gradle:9.7.1-jdk26-corretto"]
    docker --> runtime["Docker stage 2<br/>eclipse-temurin:26-jre"]
```

Source diagram: [`doc/diagrams/src/build-pipeline.mmd`](../../diagrams/src/build-pipeline.mmd).

## Narrative

### `settings.gradle`
Two-line module declaration plus plugin pinning:

- `settings.gradle:7` pins `id 'com.vaadin' version "${vaadinVersion}"` (25.2.6 from `gradle.properties:3`).
- `settings.gradle` also applies `org.gradle.toolchains.foojay-resolver-convention` so the JDK 26 toolchain can be provisioned.
- `settings.gradle` includes `cms` and `command-deck`, and `includeBuild`s `device-api` — a **standalone composite build**, not a third subproject, so [`shared-code-strategy.md`](shared-code-strategy.md)'s no-third-module decision stands. It is standalone so the driver repos can composite-include the contract without configuring the deck's Spring Boot / Vaadin build; it substitutes `ch.rupfizupfi.deck:device-api` wherever that coordinate is depended on.
- The Vaadin pre-release maven repo is added in `pluginManagement.repositories`.

### Root `build.gradle`
The root build file applies *no* plugins to the root project — it only declares them with `apply false`. Everything happens inside `subprojects { ... }`:

- `apply plugin: 'java'`, `'org.springframework.boot'`, `'io.spring.dependency-management'`, `'com.vaadin'` (lines 16-19). The Spring Boot plugin gives every subproject `bootJar` / `bootRun` tasks and the dependency-management Spring BOM.
- A common dependency block adds:
  - Vaadin: `vaadin-core` + `vaadin-spring-boot-starter`, plus `hilla-spring-boot-starter` declared explicitly — the Vaadin 25 starter does not bundle Hilla, and the React views need it.
  - Spring Boot starters: `security`, `data-jpa`, `validation`, `websocket`, and `spring-boot-starter-aspectj`. `spring-boot-devtools` and `com.vaadin:vaadin-dev` are `developmentOnly`; Vaadin 25 does not pull `vaadin-dev` in by itself.
  - Persistence: H2 (`runtimeOnly`) + PostgreSQL (`runtimeOnly`).
  - Icons: `org.parttio:line-awesome:2.1.0`.
  - Logging: `org.slf4j:slf4j-api` and `ch.qos.logback:logback-classic`, versions from the Spring Boot BOM.
- Libraries only one module uses are declared in that module's build file, not here: POI in `:cms`, jSerialComm in `:command-deck` (below).
- The Vaadin BOM is imported into `dependencyManagement` (line 51) using `${vaadinVersion}` so all `com.vaadin:*` artefacts align to 25.2.6.
- Java toolchain locked to **JDK 26** (`sourceCompatibility`, `targetCompatibility`, plus `toolchain.languageVersion`).
- `vaadin { productionMode = false; optimizeBundle = false; pnpmEnable = true }` — defaults for dev. The Docker build flips production mode with `-Pvaadin.productionMode=true` (`docker/Dockerfile:19`). `pnpmEnable` must stay in sync with `vaadin.pnpm.enable=true` in `application.properties`, or dev mode and the Gradle build populate `node_modules` differently. `useGlobalPnpm` stays off — Vaadin fetches its own pinned pnpm. Why `optimizeBundle` is off is unrecorded (OQ-16).

### `:cms/build.gradle` (cms-specific)
Renames the build outputs and adds what only cms needs:

- `bootJar.archiveBaseName = 'cms-application'` — produces `cms-application.jar` under `cms/build/libs/`.
- `jar.archiveBaseName = 'cms-library'` — Spring Boot's plugin auto-renames the plain jar to `cms-library-plain.jar` once `bootJar` is enabled (verified locally).
- Flyway (`spring-boot-starter-flyway` + `flyway-database-postgresql`), since cms owns the schema; the Testcontainers/PostgreSQL test slice.
- Office export: `org.apache.poi:poi:5.5.1` + `poi-ooxml:5.5.1` for `DownloadResults`. `:command-deck` gets them at runtime through `project(':cms')`; it compiles against no POI type.

No extra repositories, no extra plugins.

### `:command-deck/build.gradle`
On top of the root:

1. `implementation project(':cms')` — pulls in the `cms-library-plain.jar` (the plain jar, **not** the Spring Boot fat jar — Spring Boot's Gradle plugin makes `project(':cms')` resolve to the regular jar artefact). This is the only declared cross-module link; everything else flows through Spring component scan at runtime.
2. `implementation 'ch.rupfizupfi.deck:device-api:1.0.0'` — resolved by the `device-api` included build, never from a repository. Beside it `com.fazecast:jSerialComm:2.11.4`, the relay switch's serial port (`FourWayRelaySwitch`); `:cms` does not get it.
3. The [driver plugin](#driver-plugins-loaderpath-not-the-classpath) wiring: a `developmentOnly` edge onto `drivers/` and the `driverPluginTest` task. No configuration puts a driver on `bootJar`, and nothing resolves one from a repository.
4. An explicit `dependsOn` from the Vaadin/Hilla tasks onto the included build's `:jar`: the Vaadin plugin queries the runtime classpath mid-execution without declaring the dependency, so a standalone `hillaGenerate` (what `script/typecheck.ps1` runs) fails without it.

Output JAR names follow the same pattern: `command-deck-application.jar` (boot) + `command-deck-library-plain.jar` (plain).

### Driver plugins: `loader.path`, not the classpath

**No build produces a boot jar containing a driver.** `bootJar` sets `Main-Class` to `org.springframework.boot.loader.launch.PropertiesLauncher` (the pre-3.2 package name fails with `ClassNotFoundException`), which extends the classpath at launch with the directories named by `loader.path` / `LOADER_PATH`. A jar present there is loaded, a jar absent is not, and absence is a *startup* failure named by `HardwareModeCheck` — put the jar in place and restart, never rebuild. This keeps the licence-restricted `usbmodbus.jar` out of every image and out of git.

Each jar is a self-contained deck plugin, built in its own sibling repo: it implements `ch.rupfizupfi.deck:device-api` (composite-included from `device-api/` here, so **the driver repo's compile is the contract-conformance check**) and ships a Spring Boot auto-configuration registered via `META-INF/spring/...AutoConfiguration.imports` — required because the driver packages sit outside the deck's `ch.rupfizupfi.deck` component-scan root. `PropertiesLauncher` puts `loader.path` jars in the same classloader as the app, so those imports files are found exactly as a nested `BOOT-INF/lib` jar's would be. Nothing in this repo names a driver class.

Two build-side pieces; the driver path has no repository, credential or staging
task. The deck container is the simulation and test deployment, on Linux, where
both Windows-only drivers refuse to register; the bench takes drivers from
`drivers/` by hand.

| Piece | Does what |
|---|---|
| `developmentOnly fileTree('drivers')` | Puts `drivers/*.jar` on **`bootRun`'s** classpath. Unconditional: the Spring Boot plugin excludes `developmentOnly` from `bootJar`, and an empty or absent directory contributes nothing. No build flag: both driver auto-configurations are `@ConditionalOnProperty(deck.hardware.mode=real)`, mirroring the simulated pair, so `deck.hardware.mode` is the only switch. |
| `driverPluginTest` | Verification task over the jars actually in `drivers/`: each must call `DeviceApi.verifyPluginBuiltAgainst`, satisfy the current contract and register a `device-api` provider, and with both present the real context boots at `deck.hardware.mode=real` and the vendor providers must displace the simulators. Its classpath is the test runtime classpath **plus** those jars — the app first, the same precedence `loader.path` gives them — so `test` stays hermetic and a dropped-in jar never changes what the ordinary suite runs. Tagged `driver-plugin`, which `test` excludes. |

Design rationale: [`dev/backend/device-api.md`](../backend/device-api.md); what fails at startup without the jars: [`spring-boot-setup.md`](spring-boot-setup.md#hardware-mode); why no container carries one: [`ops/docker-and-profiles.md`](../../ops/docker-and-profiles.md).

### Vaadin Gradle plugin
Applied to **both** subprojects (root `build.gradle:19`). Gives each module:

- `vaadinPrepareFrontend` — generates `frontend/generated/`, `vite.generated.ts`, the `package.json` `vaadin` block (see the populated `vaadin.dependencies` map in `cms/package.json:69`). Hilla TS clients for `@BrowserCallable` services land here. Never edit `generated/`.
- `vaadinBuildFrontend` — runs Vite to bundle the React/TS app into static resources that `bootJar` packs under `META-INF/resources/`.
- The `vaadin { productionMode }` switch — production mode bundles eagerly, dev mode delegates to a Vite dev server proxied by Spring.

**The plugin runs independently per module**. Each module's `bootJar` contains its own React bundle. The CMS bundle does not see the command-deck routes; the command-deck bundle pulls cms routes in at Vite-plugin level via `command-deck/customFileSystemRouterPlugin.ts`. See [`module-layout.md`](module-layout.md) for the runtime consequence.

#### `vite.config.ts` divergence

- `cms/vite.config.ts` — three-line user config, only adds an alias `cms -> __dirname + '/src/main/frontend'`.
- `command-deck/vite.config.ts` — extends the alias to point one module up (`'../cms/src/main/frontend'`), adds:
  - the `customFileSystemRouterPlugin` (file at `command-deck/customFileSystemRouterPlugin.ts`) which regenerates cms's `file-routes.json` from cms's views and merges it into the deck's own at build start and on dev-time route updates (rules: [routing-and-layout](../frontend/routing-and-layout.md#cross-module-route-merge-deck-only)). The cms copy always lands in cms's `frontend/generated/`; the deck's sits in `frontend/generated/` in dev mode and the build `outDir` in production. Neither copy is in git — `generated/` is untracked — so both exist only after a build has run;
  - a `rollupOptions.onwarn` filter that silences Rollup's `MIXED_EXPORTS` warning — likely arising from cross-module imports.

### The driver plugin directory (`drivers/`)
One plugin directory per machine, read by `bootRun` (through `developmentOnly`), `driverPluginTest` (its own classpath) and `script/run-bench.ps1` (through `LOADER_PATH`).

No jar in it is tracked — `.gitignore` excludes `/drivers/*.jar`; only the directory's `README.md` is committed. Where each jar comes from and what its build needs: [`dev/backend/driver-jars.md`](../backend/driver-jars.md).

The JARs are not available to `:cms`, which imports no driver code. A relative `fileTree(dir: 'drivers', ...)` in the root `subprojects` block would resolve per subproject (`cms/drivers/`, `command-deck/drivers/`, neither exists) and do nothing, which is why `:command-deck` anchors its own on `rootProject.projectDir`.

### Build outputs

| Module | Boot JAR | Plain JAR | Where it ends up |
|---|---|---|---|
| `:cms` | `cms-application.jar` | `cms-library-plain.jar` | `cms/build/libs/` → `/app/app.jar` in the cms image |
| `:command-deck` | `command-deck-application.jar` | `command-deck-library-plain.jar` | `command-deck/build/libs/` → `/app/app.jar` in the deck image |

Both images build with `gradle:9.7.1-jdk26-corretto` and run on `eclipse-temurin:26-jre`. Image structure, the `*-application.jar` glob and the shared entrypoint are documented in [`ops/docker-images.md`](../../ops/docker-images.md).

### `gradle.properties`
Single source for plugin and runtime versions:

```
java.version=26
vaadinVersion=25.2.6
springBootVersion=4.1.0
project.name=breaktest command deck
project.group=ch.rupfizupfi
```

`vaadinVersion` is the only Vaadin-side pin: `hilla-spring-boot-starter` takes its version from the Vaadin BOM.

## Continuous integration

Three workflows on pull requests and pushes to `main`, all with top-level `permissions: contents: read` and a `concurrency` group (a new push cancels the PR run in flight, never a `main` run):

| Workflow | Runs | Publishes |
|---|---|---|
| [`build.yml`](../../../.github/workflows/build.yml) | `pnpm test` in both modules (Vitest, node env), `./gradlew build -Pvaadin.productionMode=true` (the JVM unit suites run inside `build`), then [`script/typecheck.ps1`](../../../script/typecheck.ps1) under ubuntu's pwsh | nothing |
| [`device-api.yml`](../../../.github/workflows/device-api.yml) | `./gradlew -p device-api build` | the contract, when its version changed; `packages: write` is granted to that job only |
| [`docker.yml`](../../../.github/workflows/docker.yml) | `docker compose config` for both profiles, then a no-push build of both images (`MODULE=cms`, `MODULE=command-deck`) with the GitHub Actions layer cache | nothing |

`docker.yml` repeats part of the Gradle work on purpose: the Dockerfile builds the boot jar inside the image, and the image build is the only check of its context, `.dockerignore` and `COPY` paths. It is gated by a `paths` filter (`docker/**`, module sources, Gradle files, `.dockerignore`) so a docs-only PR skips it; the consequence is that it cannot be a required status check without a catch-all. `.github/dependabot.yml` opens one grouped weekly PR per ecosystem: Gradle (root and the `device-api/` included build), npm (`cms/`, `command-deck/`, which reads the pnpm lockfiles), GitHub Actions, Docker (`docker/Dockerfile`) and docker-compose (the `postgres` tag). `@vaadin/*` and `@hilla/*` are ignored because the Vaadin platform version owns them.

Three things `build.yml` depends on:

- **No credentials.** Nothing in this build resolves from a private repository — not the Gradle build, not the image build. A driver is a launch-time plugin supplied by hand on the bench, so its absence cannot fail a build. `driverPluginTest` is not in CI: a Linux runner with no vendor jars can only report a skip.
- **`pnpm install --frozen-lockfile` in each module**, so the gate can resolve `tsc`. It runs before Gradle: `build` in dev mode does not pull `vaadinPrepareFrontend` into the task graph, but a production-mode build would — and that task installs with npm and deletes `pnpm-lock.yaml`, which `--frozen-lockfile` afterwards could not survive (the same hazard `script/typecheck.ps1` steps around).
- **The regenerated Hilla client is not diffed against what is committed.** It does not come back byte-identical — `generated-file-list.txt` reorders nondeterministically — so a drift check would fail on noise.

The driver repos each composite-include `device-api/` from a sibling checkout of this repo pinned to `main`, making their compile the contract-conformance check. `dscusb` publishes on a version bump; `usbmodbus` builds only, since its shadow jar bundles vendor jars that may not be redistributed.

## Where to look in the code
- `settings.gradle:1-21`
- `build.gradle:1-73` (root)
- `cms/build.gradle:1-36`
- `command-deck/build.gradle:1-100`
- `device-api/settings.gradle:1-7` and `device-api/build.gradle:1-40` (the included build)
- `gradle.properties:1-10`
- `docker/Dockerfile:1-51` (one file for both images, `MODULE` build arg)
- `command-deck/vite.config.ts:1-53`, `command-deck/customFileSystemRouterPlugin.ts` and `command-deck/routeMerge.ts`
- `drivers/README.md` (the directory's only tracked file — see [The driver plugin directory](#the-driver-plugin-directory-drivers))
- `command-deck/src/test/java/ch/rupfizupfi/deck/device/DriverPlugins.java` and `DriverPluginContractTest` / `DriverPluginBootTest` (what `driverPluginTest` runs)

To see the resolved dependency graph for either module, run
`./gradlew :command-deck:dependencies --configuration runtimeClasspath`
(or `:cms:...`).
