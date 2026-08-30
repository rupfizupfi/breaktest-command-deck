> Branch: `dev-split` — decisions taken 2026-08-17.

# Open questions

The doc-side backlog, using the canonical `OQ-n` ids.

**This file is the list of what is still open.** The *reasoning* behind
each decision lives in the reference doc that owns the topic — linked per
item. Implementation detail (files to touch, verify steps) lives in
[`06-feature-work/address-open-questions/TASKS.md`](06-feature-work/address-open-questions/TASKS.md).

Resolved and declined items are deleted, not archived — git history is the
record.

## Contents

- [Work items — what ships together](#work-items--what-ships-together)
- [Defects — something is broken or silently wrong](#defects--something-is-broken-or-silently-wrong)
- [Security and tenancy](#security-and-tenancy)
- [Investigations — outcome is a documented finding](#investigations--outcome-is-a-documented-finding)
- [Decided, awaiting implementation](#decided-awaiting-implementation)
- [Mechanical cleanups](#mechanical-cleanups)
- [Blocked or undecided](#blocked-or-undecided)

---

## Work items — what ships together

Grouping only; the `OQ-n` rows below stay canonical and every item appears in
exactly one cluster. An item is here because the others in its row change the
same files or answer the same question — splitting them across commits means
touching those files twice.

| Work item | Members | Order / gate |
|---|---|---|
| **Hardware seam** — `Drive` + `LoadCellStream` behind providers | OQ-62 | **Shipped**, see [driver-api-extraction](06-feature-work/virtual-devices/driver-api-extraction.md): the seam exists, both jars are optional to build (OQ-43's build half closed), and it also served as step 1 of [virtual-devices](06-feature-work/virtual-devices/README.md) and of [hardware-layer-redesign](06-feature-work/hardware-layer-redesign/README.md). The simulated providers and the fault switches have shipped too — what remains under OQ-62 is only the relay fake |
| **Thread lifecycle and liveness** | OQ-51, OQ-67, OQ-68, OQ-69, OQ-70, OQ-71, OQ-72 | One defect class — one flag standing for N threads, nothing joining anything, teardown that throws before it clears state — across three files. Phase 2 of [testrunner-safety](06-feature-work/testrunner-safety/README.md) owns part. **OQ-68 first**: it is a phase-1 regression, not backlog |
| **Frontend realtime** | OQ-23 | Live frames reach the browser again, so the first-batch race is observable — and worth measuring before it is fixed blind |
| **Load-cell recovery** | OQ-81 | **Shipped** — OQ-45's recovery and OQ-74's drop-and-continue both landed (2026-08-29), design of record in [loadcell-recovery-design](06-feature-work/testrunner-safety/loadcell-recovery-design.md). What survives is the residual risk OQ-81, which only the bench can settle |
| **Safety-path restructure** | OQ-64, OQ-63, OQ-49, OQ-50 | OQ-63 is a live defect — **pull it out and fix it now**, independent of the undecided OQ-64. Redesign step 3 cannot be exercised until the seam and simulator exist |
| **Driver repos** (`dscusb`, `usbmodbus`) | OQ-43, OQ-80 | Sibling repos, not this one — OQ-80 is `dscusb`-side work, gated on a bench check. Both now build from a clean checkout and both plugin jars are reproducible from their committed source (OQ-75, OQ-76 closed), so a CFW11-side change is no longer gated on a build migration. Only procurement remains |
| **Ops and deployment** | OQ-61, OQ-4, OQ-34, OQ-56 | Independent of everything above |
| **Security** | OQ-37, OQ-36 | Independent |
| **Mechanical batch** | OQ-27, OQ-38, OQ-42, OQ-82 | One commit, no decisions left |

One gate sits above the list and is **owner-owed**, not codeable:

- **Does this repo get tests?** OQ-32 and OQ-17 both block on it, as does the
  ArchUnit check the redesign needs to make its layering rule real. One
  decision, four dependants.

The resume-policy gate is **answered** (2026-08-29). The owner's numbers replaced
the invented ones and are configurable under `deck.testrunner.recovery` — table in
[`loadcell-recovery-design.md`](06-feature-work/testrunner-safety/loadcell-recovery-design.md#gates--the-numbers-and-why-they-are-configurable).
They are still uncalibrated against the bench.

---

## Defects — something is broken or silently wrong

| Id | Item | Owner doc |
|---|---|---|
| **OQ-51** | `stopThread()` NPEs when `test == null` — the `if (this.running)` guard doesn't cover it, because `running` is set before `test` is assigned. | [test-execution-engine](03-backend/test-execution-engine.md) |
| **OQ-35** | Starting a run with a parameter type that has no runner leaves `test == null` and ends silently. Needs operator-visible feedback (**not** an enum — the free-form column is deliberate). | [test-execution-engine](03-backend/test-execution-engine.md) |
| **OQ-63** | `TimeCyclicTest` divides speed by `375`, the other two runners by `0.375` (mm/rev — `TestParameter.speed` is mm/min). Its setpoints are 1000× low and the analyse-run `INITIAL_SPEED / 375` rounds to **0 rpm**. Verified in code, not on the bench. | [test-types](03-backend/test-types.md) |
| **OQ-23** | The 50 ms sleep before pushing measurements drops the first batch if the client is slow to subscribe. | [state-and-realtime](04-frontend/state-and-realtime.md) |
| **OQ-52** | `data.sql` is not idempotent. The initializer guard hides the common case, but a partially-seeded database can never recover. | [db](05-ops/db.md) |
| **OQ-19** | `mergeRoutesArrays` silently drops a parent route's own metadata (deck copy wins). | [routing-and-layout](04-frontend/routing-and-layout.md) |
| **OQ-21** | Colliding route children are silently resolved first-seen-wins. | [routing-and-layout](04-frontend/routing-and-layout.md) |
| **OQ-67** | A CFW11 poll thread abandoned by the bounded join can be resurrected by a later `tryStartThread()`: `isRunning` is one flag for what may be N threads, and `idProvider++` is a plain non-volatile `int`. Two publishers can put a stale frame on the topic *after* a fresh one. | [staleness-findings](06-feature-work/testrunner-safety/staleness-and-lifecycle-findings.md) |
| **OQ-68** | `Device.markConnectionLost()` zeroes the reference count including holders that still exist, so the *next* run's `disconnect()` reaches 0 and closes a handle the dashboard is still using. | [staleness-findings](06-feature-work/testrunner-safety/staleness-and-lifecycle-findings.md) |
| **OQ-69** | `LoadCellThread` runs forever if `cleanup()` throws before `setRunning(false)` — `log()` can throw, `stop()` has no callers, nothing joins it. Leaks a thread, an open CSV writer and a pinned load-cell reference each time. | [staleness-findings](06-feature-work/testrunner-safety/staleness-and-lifecycle-findings.md) |
| **OQ-70** | `DeviceInfoService.isEnabled` is one process-global flag rather than per-client, so one operator closing their dashboard stops broadcasting for every other tab. | [hardware-integration](03-backend/hardware-integration.md) |
| **OQ-72** | `ForceBroadcaster` flushes only when a new batch arrives, so the last ≤60 ms of a run is stranded and re-broadcast on the first sample of the next run. | [staleness-findings](06-feature-work/testrunner-safety/staleness-and-lifecycle-findings.md) |
| **OQ-77** | `TestRunnerService.start`/`stop` take `int` while `TestResult.id` is `Long`, capping runnable ids at 2³¹ with a silent failure past it. Latent (ids are small today), found while driving a simulated run. | [test-execution-engine](03-backend/test-execution-engine.md) |

## Security and tenancy

The cloud cms needs genuine per-user isolation — this is not cosmetic.
Live hardware telemetry is the deliberate exception.

| Id | Item | Owner doc |
|---|---|---|
| **OQ-37** | `@CheckUserCanOnlyAccessOwnData` covers only `SampleService` and `TestParameterService`. Audit every owner-scoped service and make the answer uniform. | [security-and-tenancy](03-backend/security-and-tenancy.md) |
| **OQ-36** | The aspect silently no-ops when the AOP target isn't a `CrudRepositoryService` — a future annotation could be purely decorative. | [security-and-tenancy](03-backend/security-and-tenancy.md) |

## Investigations — outcome is a documented finding

| Id | Item | Owner doc |
|---|---|---|
| **OQ-50** | The safe-stop escalation opens a second drive handle on the same USB device (`MotorSafetyController#stopWithFreshHandle` → `DriveProvider.open()`, reached from `retryShutdownOnException`). Tier 2 closes the old handle first and holds the drive lock across both halves, so the overlap is meant to be zero — but this is the emergency-stop path on a motor-driven rig, so establish whether two modbus handles are safe before changing anything. **Unchanged by the simulator:** fault injection now exercises the tier-2 *code path* (`DRIVE_STALE_HANDLE` reaches `FRESH_HANDLE`), which says nothing about whether two concurrent modbus sessions on one physical device are safe. That still needs hardware. | [test-execution-engine](03-backend/test-execution-engine.md) |
| **OQ-5** | `spring.main.allow-circular-references=true` papers over an unidentified cycle. The cms-side `@Lazy UserRepository` is probably the same knot. Remove the flag and read the failure. | [spring-boot-setup](02-modules/spring-boot-setup.md) |
| **OQ-56** | No `rclone` service exists in the compose file, so `docker/.env.example` cannot activate one (the previously tracked `.env` named it anyway). Purpose is off-tester backup of test result files; target, credentials and schedule are unrecorded. | [docker-and-profiles](05-ops/docker-and-profiles.md) |
| **OQ-17** | Can the Hilla generator run standalone (no JVM boot) for a CI-only TS typecheck? Answered: yes — `hillaGenerate` boots only an AOT context, and `.github/workflows/build.yml` runs `script/typecheck.ps1` on that basis. Close it. | [build-and-tooling](04-frontend/build-and-tooling.md) |
| **OQ-16** | Measure the production bundle with `optimizeBundle` on and off before flipping it. | [build-and-tooling](04-frontend/build-and-tooling.md) |
| **OQ-80** | The DSCUSB has a **continuous output mode** we do not use: `SOUT` broadcast at the configured rate, toggled with XON/XOFF, valid one-to-one — which is exactly this bench. It would remove polling entirely, and the cell is rated 200 samples/second where request/response at that rate is tight. Unreachable through `DSCUSBDrv64.dll`, so exploiting it means the serial path. **Unverified on the USB variant** — the mode is documented for the DCell & DSC family; the `STN=998` "streams without XON" claim could not be confirmed at all. Owner-flagged as interesting for near-term use; needs a bench check before anything is built. | [dscusb-serial-port](06-feature-work/dscusb-serial-port/README.md) |
| **OQ-83** | `AutoCrud.defaultCopyItem` (`cms/src/main/frontend/components/autocrud/AutoCrud.tsx:96`) clears `id` but keeps `version`, so the Copy button posts a stale non-zero version with a null id. Insert path, so probably inert — establish whether Hibernate seeds the version on persist or writes the stale value, then fix or close. | [component-inventory](04-frontend/component-inventory.md) |
| **OQ-84** | Migrating `data/package-info.java` off the Spring-7-deprecated `@NonNullApi` to JSpecify `@NullMarked` is **not** a like-for-like swap: Hilla's `NonnullPluginConfig` default matcher list has no `org.jspecify.annotations.NullMarked` entry, and `@NonNullApi` at `(false, 10)` is the only thing making every non-annotated entity field non-optional in the generated TS. A blind swap would flip the whole `data` package to optional and change `AutoForm` required-field behaviour across every CRUD view. Needs a `nonnullAnnotation` config or a different approach. | [hilla-generated-layer](04-frontend/hilla-generated-layer.md) |

## Decided, awaiting implementation

The decision is made; only the work is outstanding.

| Id | Item | Owner doc |
|---|---|---|
| **OQ-61** | Point the on-machine deck at the **cloud** Postgres. The URL is externalised now (`${DB_URL:...}`, with `DECK_DB_URL` on the deck service and no fallback on failure), so what remains is **owner-owed**: supplying the cloud host, and deciding what a link dropped *mid-run* does to a running test. | [docker-and-profiles](05-ops/docker-and-profiles.md) |
| **OQ-34** | Delete the profile-picture feature — column, `data.sql` rows, and the UI that reads it. (Decided against migrating to `FileMetadata`.) | [persistence-model](03-backend/persistence-model.md) |
| **OQ-4** | Dedupe `application*.properties`: cms copies canonical, deck copies deleted. Confirm classpath order for the profile-specific files first. | [module-layout](02-modules/module-layout.md) |
| **OQ-62** | Simulated devices so a test can run with no hardware. **Shipped except step 5:** plant model, both providers, mode enforcement, separated result root, and the fault switches that make every `LoadCellThread` detector and all three safe-stop tiers trippable on demand. What remains is only the relay fake and record/replay of real sessions — the design rates it lowest value, the relay being one fire-and-forget ASCII byte. Plant parameters stay **invented and uncalibrated**. [virtual-devices](06-feature-work/virtual-devices/README.md). | [hardware-integration](03-backend/hardware-integration.md) |
| **OQ-46** | Move the `CH9102` relay port-description literal to configuration. | [hardware-integration](03-backend/hardware-integration.md) |
| **OQ-44** | Add `Cfw11Check` alongside `FileSystemCheck` and `LoadCellCheck`. | [hardware-integration](03-backend/hardware-integration.md) |
| **OQ-71** | `TestLogger.end()` only runs from `stopThread()`, so a naturally-finished run leaks its log descriptor; `System.gc()` in `AbstractTest.destroy()` is the only thing reclaiming them. Fix `end()` first, then delete that call **and** the one in `retryShutdownOnException()`, which inserts a stop-the-world pause into the emergency stop. | [test-execution-engine](03-backend/test-execution-engine.md) |
| **OQ-28** | Split the OpenCV pipeline out of `DistanceMeasureCam.tsx`. Planned, not scheduled. | [component-inventory](04-frontend/component-inventory.md) |
| **OQ-49** | Replace `getConstructors()[0]` with an explicit constructor lookup. | [test-execution-engine](03-backend/test-execution-engine.md) |

## Mechanical cleanups

Zero-risk, no decision left in them.

| Id | Item | Owner doc |
|---|---|---|
| **OQ-38** | Replace `System.out.println` in `CheckUserCanOnlyAccessOwnDataAspect` with SLF4J at debug. | [security-and-tenancy](03-backend/security-and-tenancy.md) |
| **OQ-82** | Drop the redundant `@Nullable` from `AbstractEntity.id`/`version` and the then-unused `org.springframework.lang.Nullable` import (deprecated since Spring 7). Hilla scores `@Id` and `@Version` at the same `(nullable, 20)`, so the generated TS should not move; on the primitive `int version` the annotation is also simply false. Not worth a dedicated verify cycle — bundle into the next pass that already regenerates, and prove it by snapshot-diffing both `generated/` trees across a `typecheck.ps1` run. | [persistence-model](03-backend/persistence-model.md) |
| **OQ-27** | Rename `OnwerSelector` → `OwnerSelector` and fix its four importers. | [component-inventory](04-frontend/component-inventory.md) |
| **OQ-42** | Comment why `SettingService` implements `CrudService` directly. | [hilla-services](03-backend/hilla-services.md) |

## Blocked or undecided

| Id | Item | Why it's stuck |
|---|---|---|
| **OQ-43** | Record where `lib/usbmodbus.jar` comes from — vendor, licence holder, required version. The jar cannot be committed (licence). Narrower than it reads: the redistribution blocker is the **vendor** jars the shadow build bundles (`CommunicationLib.jar`, `ThesyconUSBLib.jar`, both 2018), not the `ch.rupfizupfi.usbmodbus` code — so splitting them apart would let the project half be committed. **Those vendor jars are committed to the `usbmodbus` remote**, but that remote is **private** (verified 2026-08-27, `gh repo view`) — so the non-redistribution position is *not* compromised upstream, and keeping that repo private is now load-bearing rather than incidental. The *build* half is **closed**: [driver-api-extraction](06-feature-work/virtual-devices/driver-api-extraction.md) shipped, so a fresh clone compiles without either jar, and its step 3 narrowed the exposure further — drivers now load at runtime from `loader.path`, so the jar is absent from git *and* from every image, reaching the tester only as a read-only host mount (`docker/drivers-local/`). Only procurement remains, and the jar is still required to *run* — `deck.hardware.mode=real` refuses to start without it. | **Owner-owed.** Only the project owner has the procurement details and can confirm the vendor-jar split is permitted. |
| **OQ-79** | Both drivers are Windows-only, so the `docker` deck profile can never drive the bench: `DSCUSBDrv64.dll` and `usbiojava_x64.dll` have no Linux build, the image base is `eclipse-temurin:26-jre`, and each driver's auto-configuration now refuses to register off Windows rather than throwing mid-run — detail in [driver-jars](03-backend/driver-jars.md#both-drivers-are-windows-only-and-that-decides-the-deployment). **The fix is decided (2026-08-29): the hardware controller stays a Windows PC** and `command-deck` runs natively on it ([bench-deployment](05-ops/bench-deployment.md)). The serial rewrite is no longer a pending choice — it survives as a documented future option, [dscusb-serial-port](06-feature-work/dscusb-serial-port/README.md), which also carries the drive-side findings (RS485 rewiring; the bundled 2015 `purejavacomm` calls `Native.setPreserveLastError`, removed in JNA 5.x, so it would `NoSuchMethodError` against the pinned JNA 5.19.1). What is left is narrower: the compose file still has a **deck** profile and `stageDrivers` still bakes a driver into a Linux image that can never use it. Retire that profile, or keep it for a future Linux host? | **Undecided, not blocking.** Nothing depends on it now that the bench path works. Interacts with OQ-61 — the deck uses the cloud database either way. |
| **OQ-64** | Whether to adopt the hardware/test-runner layer redesign — one lock per resource, `Drive` seam, per-run safety state, declarative test programs. Design in [hardware-layer-redesign](06-feature-work/hardware-layer-redesign/README.md). | **Owner-owed.** It restructures the safety path. Its step 3 is now exercisable: OQ-62's simulator and fault switches can drive all three safe-stop tiers on demand. |
| **OQ-81** | **Nothing bounds the total dropped fraction of a run.** `dscusb`'s drop budget (80 ms) is spent only on *consecutive* faults — one good reading clears it — so a cell failing alternate frames forever runs a test to completion at half sample rate, and the deck's no-data watchdog never fires because data keeps flowing. `LoadCellStream.droppedSampleCount()` is the only signal, and nothing acts on it. A session-total or rate cap was **deliberately not added**: it is another policy number, and picking one blind would trade a silent half-rate run for arbitrary aborts on a healthy bench. | **Needs the bench.** The cap can only be chosen from a real cell's fault rate, and this failure mode has never been observed. Residual risk of OQ-74's drop-and-continue. |
| **OQ-32** | Verify `TestResult.files` cascade + orphan-removal behaviour. | Needs a test suite; the repo has none and adopting one isn't decided. |
| **OQ-18** | Whether to point external tooling at Hilla's `dev/hilla/openapi.json` for a non-Hilla client. | No consumer needs it yet. Left open rather than closed. |
