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
| **Load-cell recovery** | OQ-45, OQ-74 | OQ-74 raises the cost of a glitch to "run over", so resume stops being optional. Blocked on owner-owed resume policy — see the gate below |
| **Safety-path restructure** | OQ-64, OQ-63, OQ-49, OQ-50 | OQ-63 is a live defect — **pull it out and fix it now**, independent of the undecided OQ-64. Redesign step 3 cannot be exercised until the seam and simulator exist |
| **Driver repos** (`dscusb`, `usbmodbus`) | OQ-43, OQ-75, OQ-76 | Sibling repos, not this one. OQ-76 before any CFW11-side change needs it under time pressure |
| **Ops and deployment** | OQ-61, OQ-4, OQ-14, OQ-34, OQ-56 | Independent of everything above |
| **Security** | OQ-37, OQ-36 | Independent |
| **Mechanical batch** | OQ-10, OQ-13, OQ-27, OQ-31, OQ-38, OQ-42 | One commit, no decisions left |

Two gates sit above the list and are **owner-owed**, not codeable:

- **Resume policy** (blocks OQ-45, and therefore phase 3). Every number in
  [`loadcell-recovery-design.md`](06-feature-work/testrunner-safety/loadcell-recovery-design.md#recovery-and-resume)
  — 15 min creep bound, ≤ 3 losses per run, the 10 %-of-envelope gate, the
  10 min SAFE_HOLD — is invented. They decide whether a resumed run's data is
  publishable, so they are not "decide while implementing".
- **Does this repo get tests?** OQ-32 and OQ-17 both block on it, as does the
  ArchUnit check the redesign needs to make its layering rule real. One
  decision, four dependants.

---

## Defects — something is broken or silently wrong

| Id | Item | Owner doc |
|---|---|---|
| **OQ-51** | `stopThread()` NPEs when `test == null` — the `if (this.running)` guard doesn't cover it, because `running` is set before `test` is assigned. | [test-execution-engine](03-backend/test-execution-engine.md) |
| **OQ-35** | Starting a run with a parameter type that has no runner leaves `test == null` and ends silently. Needs operator-visible feedback (**not** an enum — the free-form column is deliberate). | [test-execution-engine](03-backend/test-execution-engine.md) |
| **OQ-45** | Load-cell unplugged mid-test now trips a safe stop, but there is no recovery: the run always ends. Decided — reconnect and resume, abort only if unrecoverable. | [hardware-integration](03-backend/hardware-integration.md) |
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
| **OQ-74** | One non-finite reading now **ends the run**. The rebuilt driver throws on it, the reader thread's new catch exits, and the deck sees silence → 250 ms no-data timeout → `safeStop`. Fail-safe and better than the old behaviour (a NaN poisoning `minValue`/`maxValue`), but the cost of one transient glitch went from a corrupted statistic to a lost multi-hour cyclic run — which is what makes OQ-45's resume path mandatory rather than nice to have. Whether "one bad sample kills the stream" is the intended contract or a side effect of two independently-correct fixes is **undecided**. Never observed on the bench. | [hardware-integration](03-backend/hardware-integration.md) |

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
| **OQ-56** | `COMPOSE_PROFILES=deck,rclone` activates a service that doesn't exist. Purpose is off-tester backup of test result files; target, credentials and schedule are unrecorded. | [docker-and-profiles](05-ops/docker-and-profiles.md) |
| **OQ-17** | Can the Hilla generator run standalone (no JVM boot) for a CI-only TS typecheck? If not, record why and close. | [build-and-tooling](04-frontend/build-and-tooling.md) |
| **OQ-16** | Measure the production bundle with `optimizeBundle` on and off before flipping it. | [build-and-tooling](04-frontend/build-and-tooling.md) |
| **OQ-78** | The "one shared dev H2 file" claim is not what runs: `bootRun`'s working directory is the module dir, so three `deck.mv.db` files exist (repo root, `cms/.data/`, `command-deck/.data/`) and the modules do **not** see each other's data. Decide the intended path, align config and docs (CLAUDE.md, db.md, spring-boot-setup.md). | [db](05-ops/db.md) |

## Decided, awaiting implementation

The decision is made; only the work is outstanding.

| Id | Item | Owner doc |
|---|---|---|
| **OQ-61** | Point the on-machine deck at the **cloud** Postgres. `application-docker.properties` still hardcodes the Compose-local `db:5432`, so the decided topology isn't what runs. Needs an externalised JDBC URL and a decision on what a dropped link does to a running test. | [docker-and-profiles](05-ops/docker-and-profiles.md) |
| **OQ-14** | Fully untrack both modules' `generated/` trees. This is the cause of the permanent `git status` noise. | [hilla-generated-layer](04-frontend/hilla-generated-layer.md) |
| **OQ-34** | Delete the profile-picture feature — column, `data.sql` rows, and the UI that reads it. (Decided against migrating to `FileMetadata`.) | [persistence-model](03-backend/persistence-model.md) |
| **OQ-4** | Dedupe `application*.properties`: cms copies canonical, deck copies deleted. Confirm classpath order for the profile-specific files first. | [module-layout](02-modules/module-layout.md) |
| **OQ-62** | Simulated devices so a test can run with no hardware. **Shipped except step 5:** plant model, both providers, mode enforcement, separated result root, and the fault switches that make every `LoadCellThread` detector and all three safe-stop tiers trippable on demand. What remains is only the relay fake and record/replay of real sessions — the design rates it lowest value, the relay being one fire-and-forget ASCII byte. Plant parameters stay **invented and uncalibrated**. [virtual-devices](06-feature-work/virtual-devices/README.md). | [hardware-integration](03-backend/hardware-integration.md) |
| **OQ-46** | Move the `CH9102` relay port-description literal to configuration. | [hardware-integration](03-backend/hardware-integration.md) |
| **OQ-44** | Add `Cfw11Check` alongside `FileSystemCheck` and `LoadCellCheck`. | [hardware-integration](03-backend/hardware-integration.md) |
| **OQ-75** | Commit the `dscusb` source changes in the sibling repo. `lib/dscusb.jar` was built from an **uncommitted** working tree and committed here (`ec47aa6`), so the shipped binary has no source commit behind it and cannot be reproduced from that repo's history. It is a full restructure, not a few edits: 14 files, a package move into `ch.rupfizupfi.dscusb.dscusb` / `.t24`, `Main.kt` → `examples/Demo.kt`, plus the build migration — which means **HEAD does not build here either** (Kotlin 2.1.10, johnrengelman shadow 8.1.1, no wrapper), the same position OQ-76 describes for `usbmodbus`. The package-move hazard is **contained**: the deck now owns its own `Measurement`, so a rebuilt jar that emits `ch/rupfizupfi/dscusb/dscusb/` breaks only `CellValueStreamAdapter` and `CellValueStreamProvider` in the optional `drivers` source set, not the main tree ([driver-api-extraction](06-feature-work/virtual-devices/driver-api-extraction.md)). | [hardware-integration](03-backend/hardware-integration.md) |
| **OQ-76** | The sibling `usbmodbus` repo cannot be built on this machine: Gradle 8.10, Kotlin 1.9.23, `jvmToolchain(21)`, johnrengelman shadow 7.1.0 — the same combination that blocked `dscusb` until it was modernised to Gradle 9.7 / Kotlin 2.4.10 / JVM 26 / gradleup shadow. Installed JDKs are 26 and corretto-19. Nothing needs it today — the `Drive` seam compiles against the **shipped** jar, and [driver-api-extraction](06-feature-work/virtual-devices/driver-api-extraction.md) shipped without touching the sibling repo. What does need it: any change to the CFW11 side, including OQ-50's dual-handle investigation and tier-2 fresh-handle work. Apply the same modernisation before something urgent needs it. | [hardware-integration](03-backend/hardware-integration.md) |
| **OQ-71** | `TestLogger.end()` only runs from `stopThread()`, so a naturally-finished run leaks its log descriptor; `System.gc()` in `AbstractTest.destroy()` is the only thing reclaiming them. Fix `end()` first, then delete that call **and** the one in `retryShutdownOnException()`, which inserts a stop-the-world pause into the emergency stop. | [test-execution-engine](03-backend/test-execution-engine.md) |
| **OQ-28** | Split the OpenCV pipeline out of `DistanceMeasureCam.tsx`. Planned, not scheduled. | [component-inventory](04-frontend/component-inventory.md) |
| **OQ-49** | Replace `getConstructors()[0]` with an explicit constructor lookup. | [test-execution-engine](03-backend/test-execution-engine.md) |

## Mechanical cleanups

Zero-risk, no decision left in them.

| Id | Item | Owner doc |
|---|---|---|
| **OQ-10** | Drop the unreferenced `hillaVersion` from `gradle.properties`. | [gradle-build](02-modules/gradle-build.md) |
| **OQ-13** | Delete the empty `command-deck/.../hilla/` package tree. | [shared-code-strategy](02-modules/shared-code-strategy.md) |
| **OQ-31** | Delete the dead `Avatar` import in cms `views/@layout.tsx:4`. | [frontend-module-split](04-frontend/frontend-module-split.md) |
| **OQ-38** | Replace `System.out.println` in `CheckUserCanOnlyAccessOwnDataAspect` with SLF4J at debug. | [security-and-tenancy](03-backend/security-and-tenancy.md) |
| **OQ-27** | Rename `OnwerSelector` → `OwnerSelector` and fix its four importers. | [component-inventory](04-frontend/component-inventory.md) |
| **OQ-42** | Comment why `SettingService` implements `CrudService` directly. | [hilla-services](03-backend/hilla-services.md) |

## Blocked or undecided

| Id | Item | Why it's stuck |
|---|---|---|
| **OQ-43** | Record where `lib/usbmodbus.jar` comes from — vendor, licence holder, required version. The jar cannot be committed (licence). Narrower than it reads: the redistribution blocker is the **vendor** jars the shadow build bundles (`CommunicationLib.jar`, `ThesyconUSBLib.jar`, both 2018), not the `ch.rupfizupfi.usbmodbus` code — so splitting them apart would let the project half be committed. The *build* half is **closed**: [driver-api-extraction](06-feature-work/virtual-devices/driver-api-extraction.md) shipped, so a fresh clone compiles without either jar. Only procurement remains, and the jar is still required to *run* — `deck.hardware.mode=real` refuses to start without it. | **Owner-owed.** Only the project owner has the procurement details and can confirm the vendor-jar split is permitted. |
| **OQ-64** | Whether to adopt the hardware/test-runner layer redesign — one lock per resource, `Drive` seam, per-run safety state, declarative test programs. Design in [hardware-layer-redesign](06-feature-work/hardware-layer-redesign/README.md). | **Owner-owed.** It restructures the safety path. Its step 3 is now exercisable: OQ-62's simulator and fault switches can drive all three safe-stop tiers on demand. |
| **OQ-32** | Verify `TestResult.files` cascade + orphan-removal behaviour. | Needs a test suite; the repo has none and adopting one isn't decided. |
| **OQ-18** | Whether to point external tooling at Hilla's `dev/hilla/openapi.json` for a non-Hilla client. | No consumer needs it yet. Left open rather than closed. |
