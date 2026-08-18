> Branch: `dev-split` — refreshed 2026-08-17 after decisions.

# Tasks — implementation detail

**What** is open and **why** lives in
[`../../OPEN-QUESTIONS.md`](../../OPEN-QUESTIONS.md). This file is only
*how*: files to touch, the change, how to verify. Decisions and rejected
alternatives are in
[`DECISIONS.md`](DECISIONS.md).

Status legend: `[ ]` not started · `[~]` in progress. Finished items are
deleted from this file, not ticked.

**Coverage is partial.** OQ-67 to OQ-72 were filed after this file was
last refreshed and have no entry here yet; their detail lives in
[`../testrunner-safety/staleness-and-lifecycle-findings.md`](../testrunner-safety/staleness-and-lifecycle-findings.md).
`OPEN-QUESTIONS.md` remains the complete list.

---

## Contents

- [Quick wins — mechanical, zero decision left](#quick-wins--mechanical-zero-decision-left)
  - [[ ] OQ-10 · Drop unused `hillaVersion`](#--oq-10--drop-unused-hillaversion)
  - [[ ] OQ-13 · Delete empty `hilla/` package tree](#--oq-13--delete-empty-hilla-package-tree)
  - [[ ] OQ-31 · Remove dead `Avatar` import](#--oq-31--remove-dead-avatar-import)
  - [[ ] OQ-38 · `System.out.println` → SLF4J debug](#--oq-38--systemoutprintln--slf4j-debug)
  - [[ ] OQ-42 · Comment `SettingService`'s direct `CrudService`](#--oq-42--comment-settingservices-direct-crudservice)
  - [[ ] OQ-27 · Rename `OnwerSelector` → `OwnerSelector`](#--oq-27--rename-onwerselector--ownerselector)
  - [[ ] OQ-51 · Null-guard `stopThread()`](#--oq-51--null-guard-stopthread)
- [Correctness](#correctness)
  - [[ ] OQ-35 · Surface unrunnable parameter types to the operator](#--oq-35--surface-unrunnable-parameter-types-to-the-operator)
  - [[ ] OQ-23 · Replace the 50 ms handshake sleep](#--oq-23--replace-the-50-ms-handshake-sleep)
  - [[ ] OQ-52 · Make `data.sql` idempotent](#--oq-52--make-datasql-idempotent)
  - [[ ] OQ-19 · Document `mergeRoutesArrays`' metadata limitation](#--oq-19--document-mergeroutesarrays-metadata-limitation)
  - [[ ] OQ-21 · Warn on colliding route children](#--oq-21--warn-on-colliding-route-children)
- [Security](#security)
  - [[ ] OQ-37 · Audit owner-scoping coverage](#--oq-37--audit-owner-scoping-coverage)
  - [[ ] OQ-36 · Make the aspect fail loudly on non-CRUD targets](#--oq-36--make-the-aspect-fail-loudly-on-non-crud-targets)
- [Hardware](#hardware)
- [Ops](#ops)
  - [[ ] OQ-61 · Point deck at the cloud Postgres](#--oq-61--point-deck-at-the-cloud-postgres)
  - [[ ] OQ-4 · Dedupe `application*.properties`](#--oq-4--dedupe-applicationproperties)
  - [[ ] OQ-14 · Fully untrack `generated/`](#--oq-14--fully-untrack-generated)
  - [[ ] OQ-34 · Delete the profile-picture feature](#--oq-34--delete-the-profile-picture-feature)
  - [[ ] OQ-56 · Define or drop the `rclone` service](#--oq-56--define-or-drop-the-rclone-service)
- [Investigations](#investigations)
  - [[ ] OQ-5 · Find the circular dependency](#--oq-5--find-the-circular-dependency)
  - [[ ] OQ-16 · Measure the production bundle](#--oq-16--measure-the-production-bundle)
  - [[ ] OQ-17 · Standalone Hilla generator for CI typecheck](#--oq-17--standalone-hilla-generator-for-ci-typecheck)
  - [[ ] OQ-32 · Verify `TestResult.files` cascade — **blocked**](#--oq-32--verify-testresultfiles-cascade--blocked)
  - [[ ] OQ-18 · OpenAPI alternative client — **undecided**](#--oq-18--openapi-alternative-client--undecided)
  - [[ ] OQ-28 · Split OpenCV out of the webcam component](#--oq-28--split-opencv-out-of-the-webcam-component)
  - [[ ] OQ-49 · Explicit constructor lookup in `TestRunnerFactory`](#--oq-49--explicit-constructor-lookup-in-testrunnerfactory)

## Quick wins — mechanical, zero decision left

### [ ] OQ-10 · Drop unused `hillaVersion`
- **File:** `gradle.properties:1`
- **Change:** delete the line. Nothing reads it; the Hilla starter's version comes from the Vaadin BOM.
- **Verify:** `./gradlew build`.

### [ ] OQ-13 · Delete empty `hilla/` package tree
- **Path:** `command-deck/src/main/java/ch/rupfizupfi/deck/hilla/` (contains empty `crud/` and `mappedtypes/`)
- **Change:** remove the directories.

### [ ] OQ-31 · Remove dead `Avatar` import
- **File:** `cms/src/main/frontend/views/@layout.tsx:4`
- **Change:** delete the `import { Avatar } from '@vaadin/react-components/Avatar.js';` line.

### [ ] OQ-38 · `System.out.println` → SLF4J debug
- **File:** `cms/src/main/java/ch/rupfizupfi/deck/security/CheckUserCanOnlyAccessOwnDataAspect.java:22` and `:40`
- **Change:** add `private static final Logger log = LoggerFactory.getLogger(...)`; convert both calls to `log.debug(...)`.

### [ ] OQ-42 · Comment `SettingService`'s direct `CrudService`
- **File:** `cms/src/main/java/ch/rupfizupfi/deck/api/services/SettingService.java`
- **Change:** one line explaining `Setting` is file-backed (`settings.json`), not a JPA entity, so `CrudRepositoryService` doesn't apply.

### [ ] OQ-27 · Rename `OnwerSelector` → `OwnerSelector`
- **Rename:** `cms/src/main/frontend/components/owner/OnwerSelector.tsx` → `OwnerSelector.tsx`
- **Importers to update:** `cms/.../components/autocrud/sample.tsx`, `cms/.../components/autocrud/test.tsx`, `cms/.../views/project/@index.tsx`, `command-deck/.../views/run.tsx` (already aliases it to the correct spelling).

### [ ] OQ-51 · Null-guard `stopThread()`
- **File:** `command-deck/src/main/java/ch/rupfizupfi/deck/testrunner/TestRunnerThread.java:82`
- **Change:** guard `this.test.getContext().sendSignal(0)` with `test != null`. Note `running` is set before `test` is assigned, so the existing `if (this.running)` is not sufficient.

---

## Correctness

### [ ] OQ-35 · Surface unrunnable parameter types to the operator
- **File:** `command-deck/src/main/java/ch/rupfizupfi/deck/testrunner/TestRunnerThread.java` (the `switch` on `testResult.testParameter.type`)
- **Change:** on the `default` branch, log via `testLogger` so the operator sees why nothing ran. Do **not** convert the column to an enum.
- **Verify:** start a run with a parameter type other than `destructive`/`cyclic`/`timeCyclic`; the UI log shows a clear message.

### [ ] OQ-23 · Replace the 50 ms handshake sleep
- **File:** `command-deck/src/main/java/ch/rupfizupfi/deck/testrunner/TestRunnerThread.java` (`Thread.sleep(50)` at the top of `run()`)
- **Change:** wait for an inbound STOMP subscription/handshake message instead of guessing. `StatusService.sendStatusRequest` already publishes to `/topic/requests` — a ready signal could reuse that path.

### [ ] OQ-52 · Make `data.sql` idempotent
- **File:** `cms/src/main/resources/data.sql`
- **Change:** `INSERT ... ON CONFLICT DO NOTHING` on every seed row.
- **Verify:** syntax works on **both** H2 (dev) and Postgres (docker). Note the initializer already gates on `UserRepository.count() == 0`, so this is about recovering partially-seeded databases, not the normal boot path.

### [ ] OQ-19 · Document `mergeRoutesArrays`' metadata limitation
- **File:** the route-merge plugin (grep for `mergeRoutesArrays`)
- **Change:** comment at the merge site: children merge, parent metadata does not — the deck copy wins.
- **Note:** a regression test would be better, but no frontend test framework is being adopted.

### [ ] OQ-21 · Warn on colliding route children
- **File:** same plugin as OQ-19
- **Change:** `console.warn` naming both source paths when a child path collides.

---

## Security

### [ ] OQ-37 · Audit owner-scoping coverage
- **Scope:** every service handling a `DataWithOwner` entity — `ProjectService`, `TestResultService`, `FileMetadataService`, plus the two that already carry the annotation (`SampleService`, `TestParameterService`).
- **Change:** decide per service whether `CrudRepositoryServiceForOwnerData` alone is sufficient, then make it uniform. The cloud cms needs real isolation, so "probably fine" is not an acceptable outcome.
- **Deliverable:** a short table in `03-backend/security-and-tenancy.md` — service, entity, what enforces ownership.

### [ ] OQ-36 · Make the aspect fail loudly on non-CRUD targets
- **File:** `cms/src/main/java/ch/rupfizupfi/deck/security/CheckUserCanOnlyAccessOwnDataAspect.java`
- **Change:** narrow the pointcut to `CrudRepositoryService+`, or throw when the target isn't one, so the annotation cannot be decorative.

---

## Hardware

Moved to [`TASKS-hardware.md`](TASKS-hardware.md) — OQ-45, OQ-46, OQ-44,
OQ-50, OQ-43, OQ-74, OQ-75, OQ-76, including the two sibling driver repos.

---

## Ops

### [ ] OQ-61 · Point deck at the cloud Postgres
- **Files:** `command-deck` / `cms` `application-docker.properties` (currently `jdbc:postgresql://db:5432/rupfizupfi`), `docker/docker-compose.yaml` (the `db` service is started for both profiles)
- **Change:** externalise the JDBC URL so the tester can target the cloud host.
- **Also decide:** what happens to a running test if the database link drops mid-run.

### [ ] OQ-4 · Dedupe `application*.properties`
- **Files:** `command-deck/src/main/resources/application{,-dev,-docker}.properties`
- **Change:** delete the deck copies and rely on the cms classpath copies, or import them explicitly.
- **Verify first:** classpath ordering for the *profile-specific* files — `application.properties` and `application-dev.properties` are byte-identical and `application-docker.properties` differs only in line endings, so nothing is lost if resolution works as expected. Boot both modules in both profiles.

### [ ] OQ-14 · Fully untrack `generated/`
- **Change:** `git rm --cached -r` both modules' `src/main/frontend/generated/` trees; confirm `.gitignore` covers them.
- **Verify:** clean clone + build produces a working frontend with no tracked generated files. This is what removes the permanent `git status` noise.

### [ ] OQ-34 · Delete the profile-picture feature
- **Scope:** the image column on `User` / `application_user`, its rows in `cms/src/main/resources/data.sql`, and any UI reading it.
- **Note:** with `ddl-auto=update` Hibernate will not drop the column — do it manually on the live database.

### [ ] OQ-56 · Define or drop the `rclone` service
- **Files:** `docker/.env` (`COMPOSE_PROFILES=deck,rclone`), `docker/docker-compose.yaml`
- **Intent:** back up test result files off the tester. **Do not just delete the profile** — the intent is real; the service definition is missing.
- **Needed:** remote target, credential handling, schedule, and what gets backed up (the `docker/breaktester/` bind mount holds the CSV results).

---

## Investigations

### [ ] OQ-5 · Find the circular dependency
- **Change:** remove `spring.main.allow-circular-references=true` from `application.properties`, boot both modules, read the failure.
- **Likely related:** the cms-side `@Lazy UserRepository` in `Application.java` exists to break a startup cycle — probably the same knot.
- **Hypothesis to test first** (from the original triage — reasoned from the wiring, never measured at runtime):

  ```
  SecurityConfiguration  →  UserDetailsServiceImpl  →  UserRepository
                         →  AuthenticatedUser       →  UserRepository
  CheckUserCanOnlyAccessOwnDataAspect (@Aspect)    →  AuthenticatedUser (field-injected)
  SqlDataSourceScriptDatabaseInitializer (@Bean)   →  UserRepository (constructor)
  ```

  While `UserRepository` is being proxied for AOP weaving, the aspect wants
  `AuthenticatedUser`, which wants `UserRepository` — a self-referential closure.
- **Fix path if the hypothesis holds:** switch the aspect's `AuthenticatedUser` from field injection to an `ObjectProvider<AuthenticatedUser>` lazy lookup, then drop the flag from both modules' `application.properties` and confirm neither app throws `BeanCurrentlyInCreationException`.
- **Deliverable:** either the cycle broken properly, or a comment naming the beans involved and why the flag stays.

### [ ] OQ-16 · Measure the production bundle
- **Change:** build with `optimizeBundle` off (current) and on, compare output size, then decide.
- **Watch for:** the cross-module alias interacting with tree-shaking — the likeliest reason it was disabled.

### [ ] OQ-17 · Standalone Hilla generator for CI typecheck
- **Question:** can the generator run without booting the Spring app? If yes, wire a `typecheck` script running generator + `tsc --noEmit`. If no, record why and close.

### [ ] OQ-32 · Verify `TestResult.files` cascade — **blocked**
- **Blocked on:** adopting a test framework. The repo has none and that isn't decided.

### [ ] OQ-18 · OpenAPI alternative client — **undecided**
- No consumer needs a non-Hilla client today.

### [ ] OQ-28 · Split OpenCV out of the webcam component
- **File:** `command-deck/.../components/DistanceMeasureCam.tsx`
- **Change:** pipeline becomes a plain class exposing an observable; the React component only subscribes and renders. Planned, not scheduled.

### [ ] OQ-49 · Explicit constructor lookup in `TestRunnerFactory`
- **File:** `command-deck/src/main/java/ch/rupfizupfi/deck/testrunner/TestRunnerFactory.java` (`getConstructors()[0]`)
- **Change:** look the constructor up by parameter types so adding a second public constructor can't silently change behaviour.
