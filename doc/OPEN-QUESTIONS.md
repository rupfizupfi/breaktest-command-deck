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
- [Blocked or undecided](#blocked-or-undecided)

---

## Work items — what ships together

Grouping only; the `OQ-n` rows below stay canonical and every item appears in
exactly one cluster. An item is here because the others in its row change the
same files or answer the same question — splitting them across commits means
touching those files twice.

| Work item | Members | Order / gate |
|---|---|---|
| **Hardware seam** — `Drive` + `LoadCellStream` behind providers | OQ-62 | **Shipped**, see [driver-api-extraction](06-feature-work/virtual-devices/driver-api-extraction.md): the seam exists, both jars are optional to build (OQ-43's build half closed), and it also served as step 1 of [virtual-devices](06-feature-work/virtual-devices/README.md) and of [hardware-layer-redesign](06-feature-work/hardware-layer-redesign/README.md). The simulated providers, the fault switches and the relay fake have shipped too — what remains under OQ-62 is only record/replay of real sessions |
| **Thread lifecycle and liveness** | OQ-68, OQ-70 | One defect class — one flag standing for N threads, nothing joining anything, teardown that throws before it clears state — across three files. Phase 2 of [testrunner-safety](06-feature-work/testrunner-safety/README.md) has **shipped**, closing the `TestRunnerThread` half (start-path failure propagates, an unknown type faults the run by name, the test log closes on every exit, both `System.gc()` calls gone). **OQ-68 first**: it is a phase-1 regression, not backlog. OQ-72 closed: `LoadCellDevice#closeConnection` flushes the observers, so the tail batch leaves with the run that produced it. OQ-67 closed: each CFW11 poll thread now owns its stop flag, so a thread abandoned by the bounded join stays stopped and publishes nothing. OQ-69 closed: teardown no longer depends on a healthy component - `closeConnection` clears the field before it calls `stopReading()`, `TestLogger.log()` absorbs a rejected broadcast, `end()` claims the writer in one read, and `cleanup()` reaches `LoadCellThread#stop()`, which interrupts and joins |
| **Frontend realtime** | OQ-23 | Live frames reach the browser again, so the first-batch race is observable — and worth measuring before it is fixed blind |
| **Load-cell recovery** | OQ-81 | **Shipped** — OQ-45's recovery and OQ-74's drop-and-continue both landed (2026-08-29), design of record in [loadcell-recovery-design](06-feature-work/testrunner-safety/loadcell-recovery-design.md). What survives is the residual risk OQ-81, which only the bench can settle |
| **Safety-path restructure** | OQ-64, OQ-63, OQ-50 | OQ-63 is a live defect — **pull it out and fix it now**, independent of the undecided OQ-64. Redesign step 3 cannot be exercised until the seam and simulator exist |
| **Driver repos** (`dscusb`, `usbmodbus`) | OQ-43, OQ-80 | Sibling repos, not this one — OQ-80 is `dscusb`-side work, gated on a bench check. Both now build from a clean checkout and both plugin jars are reproducible from their committed source (OQ-75, OQ-76 closed), so a CFW11-side change is no longer gated on a build migration. Only procurement remains |
| **Ops and deployment** | OQ-61, OQ-4, OQ-34, OQ-56 | Independent of everything above |
| **Security** | OQ-37 | Independent |

One gate sits above the list and is **owner-owed**, not codeable:

- **Does this repo get tests? Decided yes (2026-08-30):** a plain-JUnit unit
  suite over the testrunner safety logic, a Testcontainers/Postgres
  `@DataJpaTest` slice in cms (answers OQ-32), per-module context tests (which
  override the datasource to in-memory H2 — the default `dev` profile's
  file-backed H2 must never be opened by a test), and a node-env Vitest rung
  for frontend logic. Still open: the ArchUnit check the redesign needs.

The resume-policy gate is **answered** (2026-08-29). The owner's numbers replaced
the invented ones and are configurable under `deck.testrunner.recovery` — table in
[`loadcell-recovery-design.md`](06-feature-work/testrunner-safety/loadcell-recovery-design.md#gates--the-numbers-and-why-they-are-configurable).
They are still uncalibrated against the bench.

---

## Defects — something is broken or silently wrong

| Id | Item | Owner doc |
|---|---|---|
| **OQ-63** | `TimeCyclicTest` divides speed by `375`, the other two runners by `0.375` (mm/rev — `TestParameter.speed` is mm/min). Its setpoints are 1000× low and the analyse-run `INITIAL_SPEED / 375` rounds to **0 rpm**. Verified in code, not on the bench. | [test-types](03-backend/test-types.md) |
| **OQ-23** | The 50 ms sleep before pushing measurements drops the first batch if the client is slow to subscribe. | [state-and-realtime](04-frontend/state-and-realtime.md) |
| **OQ-52** | `data.sql` is not idempotent. The initializer guard hides the common case, but a partially-seeded database can never recover. | [db](05-ops/db.md) |
| **OQ-19** | `mergeRoutesArrays` silently drops a parent route's own metadata (deck copy wins). | [routing-and-layout](04-frontend/routing-and-layout.md) |
| **OQ-21** | Colliding route children are silently resolved first-seen-wins. | [routing-and-layout](04-frontend/routing-and-layout.md) |
| **OQ-68** | `Device.markConnectionLost()` zeroes the reference count including holders that still exist, so the *next* run's `disconnect()` reaches 0 and closes a handle the dashboard is still using. | [staleness-findings](06-feature-work/testrunner-safety/staleness-and-lifecycle-findings.md) |
| **OQ-70** | `DeviceInfoService.isEnabled` is one process-global flag rather than per-client, so one operator closing their dashboard stops broadcasting for every other tab. | [hardware-integration](03-backend/hardware-integration.md) |

## Security and tenancy

The cloud cms needs genuine per-user isolation — this is not cosmetic.
Live hardware telemetry is the deliberate exception.

| Id | Item | Owner doc |
|---|---|---|
| **OQ-37** | Every owned CRUD service extends `CrudRepositoryServiceForOwnerData`, which scopes `get`, `list`, `delete`, `save` and `saveAll` by specification, and `TestResultService`'s CSV reads gate on the scoped `get`. The bulk forms are covered too: `deleteAll` resolves every id through the scoped `get` and refuses the whole batch on one foreign id, and `FileMetadataService` routes `saveAll`/`deleteAll` through its own per-row check. What is left: `FileMetadataService` hand-rolls that rule through the file's `TestResult` on every entry point, because `FileMetadata` is no `DataWithOwner`; and `@RolesAllowed` on a `@BrowserCallable` method is enforced only over HTTP, so a role audit needs a request-path test. The per-service table is in the owner doc. | [security-and-tenancy](03-backend/security-and-tenancy.md) |

## Investigations — outcome is a documented finding

| Id | Item | Owner doc |
|---|---|---|
| **OQ-50** | The safe-stop escalation opens a second drive handle on the same USB device (`MotorSafetyController#stopWithFreshHandle` → `DriveProvider.open()`, reached from `retryShutdownOnException`). Tier 2 closes the old handle first and holds the drive lock across both halves, so the overlap is meant to be zero — but this is the emergency-stop path on a motor-driven rig, so establish whether two modbus handles are safe before changing anything. **Unchanged by the simulator:** fault injection now exercises the tier-2 *code path* (`DRIVE_STALE_HANDLE` reaches `FRESH_HANDLE`), which says nothing about whether two concurrent modbus sessions on one physical device are safe. That still needs hardware. | [test-execution-engine](03-backend/test-execution-engine.md) |
| **OQ-5** | `spring.main.allow-circular-references=true` papers over an unidentified cycle. The cms-side `@Lazy UserRepository` is probably the same knot. Remove the flag and read the failure. | [spring-boot-setup](02-modules/spring-boot-setup.md) |
| **OQ-56** | No `rclone` service exists in the compose file, so `docker/.env.example` cannot activate one (the previously tracked `.env` named it anyway). Purpose is off-tester backup of test result files; target, credentials and schedule are unrecorded. | [docker-and-profiles](05-ops/docker-and-profiles.md) |
| **OQ-17** | Can the Hilla generator run standalone (no JVM boot) for a CI-only TS typecheck? Answered: yes — `hillaGenerate` boots only an AOT context, and `.github/workflows/build.yml` runs `script/typecheck.ps1` on that basis. Close it. | [build-and-tooling](04-frontend/build-and-tooling.md) |
| **OQ-16** | Measure the production bundle with `optimizeBundle` on and off before flipping it. | [build-and-tooling](04-frontend/build-and-tooling.md) |
| **OQ-80** | The DSCUSB has a **continuous output mode** we do not use: `SOUT` broadcast at the configured rate, toggled with XON/XOFF, valid one-to-one — which is exactly this bench. It would remove polling entirely, and the cell is rated 200 samples/second where request/response at that rate is tight. Unreachable through `DSCUSBDrv64.dll`, so exploiting it means the serial path. **Unverified on the USB variant** — the mode is documented for the DCell & DSC family; the `STN=998` "streams without XON" claim could not be confirmed at all. Owner-flagged as interesting for near-term use; needs a bench check before anything is built. | [dscusb-serial-port](06-feature-work/dscusb-serial-port/README.md) |
| **OQ-84** | Migrating `data/package-info.java` off the Spring-7-deprecated `@NonNullApi` to JSpecify `@NullMarked` is **not** a like-for-like swap: Hilla's `NonnullPluginConfig` default matcher list has no `org.jspecify.annotations.NullMarked` entry, and `@NonNullApi` at `(false, 10)` is the only thing making every non-annotated entity field non-optional in the generated TS. A blind swap would flip the whole `data` package to optional and change `AutoForm` required-field behaviour across every CRUD view. Needs a `nonnullAnnotation` config or a different approach. | [hilla-generated-layer](04-frontend/hilla-generated-layer.md) |

## Decided, awaiting implementation

The decision is made; only the work is outstanding.

| Id | Item | Owner doc |
|---|---|---|
| **OQ-61** | Point the on-machine deck at the **cloud** Postgres. The URL is externalised now (`${DB_URL:...}`, with `DECK_DB_URL` on the deck service and no fallback on failure), so what remains is **owner-owed**: supplying the cloud host, and deciding what a link dropped *mid-run* does to a running test. | [docker-and-profiles](05-ops/docker-and-profiles.md) |
| **OQ-34** | Delete the profile-picture feature — column, `data.sql` rows, and the UI that reads it. (Decided against migrating to `FileMetadata`.) | [persistence-model](03-backend/persistence-model.md) |
| **OQ-4** | Dedupe `application*.properties`: cms copies canonical, deck copies deleted. Confirm classpath order for the profile-specific files first. | [module-layout](02-modules/module-layout.md) |
| **OQ-62** | Simulated devices so a test can run with no hardware. **Shipped except half of step 5:** plant model, all three providers, mode enforcement, separated result root, the fault switches that make every `LoadCellThread` detector and all three safe-stop tiers trippable on demand, and the relay fake behind `SuckService` as the relay's single owner. What remains is record/replay of real sessions, which the design rates lowest value. Plant parameters stay **invented and uncalibrated**. [virtual-devices](06-feature-work/virtual-devices/README.md). | [hardware-integration](03-backend/hardware-integration.md) |
| **OQ-44** | `FrequencyInverterCheck` ships alongside `FileSystemCheck` and `LoadCellCheck`, proving the drive answers a read-only round trip. What remains is the device-identity handshake (serial, model) the check would need to tell the real drive from a simulated one. | [hardware-integration](03-backend/hardware-integration.md) |
| **OQ-28** | Split the OpenCV pipeline out of `DistanceMeasureCam.tsx`. Planned, not scheduled. | [component-inventory](04-frontend/component-inventory.md) |

## Blocked or undecided

| Id | Item | Why it's stuck |
|---|---|---|
| **OQ-43** | Record where `drivers/usbmodbus.jar` comes from — vendor, licence holder, required version. The jar cannot be committed (licence). Narrower than it reads: the redistribution blocker is the **vendor** jars the shadow build bundles (`CommunicationLib.jar`, `ThesyconUSBLib.jar`, both 2018), not the `ch.rupfizupfi.usbmodbus` code — so splitting them apart would let the project half be committed. **Those vendor jars are committed to the `usbmodbus` remote**, but that remote is **private** (verified 2026-08-27, `gh repo view`) — so the non-redistribution position is *not* compromised upstream, and keeping that repo private is now load-bearing rather than incidental. The *build* half is **closed**: [driver-api-extraction](06-feature-work/virtual-devices/driver-api-extraction.md) shipped, so a fresh clone compiles without either jar, and its step 3 narrowed the exposure further — drivers now load at runtime from `loader.path`, so the jar is absent from git *and* from every image, reaching the bench only as a hand-copied file in `drivers/` — no image carries a driver at all since OQ-79 closed. Only procurement remains, and the jar is still required to *run* — `deck.hardware.mode=real` refuses to start without it. | **Owner-owed.** Only the project owner has the procurement details and can confirm the vendor-jar split is permitted. |
| **OQ-64** | Whether to adopt the hardware/test-runner layer redesign — one lock per resource, `Drive` seam, per-run safety state, declarative test programs. Design in [hardware-layer-redesign](06-feature-work/hardware-layer-redesign/README.md). | **Owner-owed.** It restructures the safety path. Its step 3 is now exercisable: OQ-62's simulator and fault switches can drive all three safe-stop tiers on demand. |
| **OQ-81** | **Nothing bounds the total dropped fraction of a run.** `dscusb`'s drop budget (80 ms) is spent only on *consecutive* faults — one good reading clears it — so a cell failing alternate frames forever runs a test to completion at half sample rate, and the deck's no-data watchdog never fires because data keeps flowing. `LoadCellStream.droppedSampleCount()` is the only signal, and nothing acts on it. A session-total or rate cap was **deliberately not added**: it is another policy number, and picking one blind would trade a silent half-rate run for arbitrary aborts on a healthy bench. | **Needs the bench.** The cap can only be chosen from a real cell's fault rate, and this failure mode has never been observed. Residual risk of OQ-74's drop-and-continue. |
| **OQ-32** | Verify `TestResult.files` cascade + orphan-removal behaviour. | **Answered 2026-08-30** by cms `TestResultPersistenceTest` (Testcontainers, real Postgres): cascade insert, delete-cascade and orphan removal all behave as annotated, and the recovery finder round-trips `RunStatus` by name. Closable. |
| **OQ-18** | Whether to point external tooling at Hilla's `dev/hilla/openapi.json` for a non-Hilla client. | No consumer needs it yet. Left open rather than closed. |
