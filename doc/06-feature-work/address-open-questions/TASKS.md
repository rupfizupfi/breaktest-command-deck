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

- [Correctness](#correctness)
  - [[ ] OQ-23 · Replace the 50 ms handshake sleep](#--oq-23--replace-the-50-ms-handshake-sleep)
  - [[ ] OQ-52 · Make `data.sql` idempotent](#--oq-52--make-datasql-idempotent)
  - [[ ] OQ-19 · Document `mergeRoutesArrays`' metadata limitation](#--oq-19--document-mergeroutesarrays-metadata-limitation)
  - [[ ] OQ-21 · Warn on colliding route children](#--oq-21--warn-on-colliding-route-children)
- [Security](#security)
  - [[~] OQ-37 · Audit owner-scoping coverage](#-oq-37--audit-owner-scoping-coverage)
- [Hardware](#hardware)
- [Ops](#ops)
  - [[ ] OQ-61 · Point deck at the cloud Postgres](#--oq-61--point-deck-at-the-cloud-postgres)
  - [[ ] OQ-4 · Dedupe `application*.properties`](#--oq-4--dedupe-applicationproperties)
  - [[ ] OQ-34 · Delete the profile-picture feature](#--oq-34--delete-the-profile-picture-feature)
  - [[ ] OQ-56 · Define or drop the `rclone` service](#--oq-56--define-or-drop-the-rclone-service)
- [Investigations](#investigations)
  - [[ ] OQ-5 · Find the circular dependency](#--oq-5--find-the-circular-dependency)
  - [[ ] OQ-16 · Measure the production bundle](#--oq-16--measure-the-production-bundle)
  - [[ ] OQ-17 · Standalone Hilla generator for CI typecheck](#--oq-17--standalone-hilla-generator-for-ci-typecheck)
  - [[ ] OQ-32 · Verify `TestResult.files` cascade — **blocked**](#--oq-32--verify-testresultfiles-cascade--blocked)
  - [[ ] OQ-18 · OpenAPI alternative client — **undecided**](#--oq-18--openapi-alternative-client--undecided)
  - [[ ] OQ-28 · Split OpenCV out of the webcam component](#--oq-28--split-opencv-out-of-the-webcam-component)

## Correctness

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

### [~] OQ-37 · Audit owner-scoping coverage
- **Done:** every owned CRUD service — `SampleService` and `TestParameterService` included — extends `CrudRepositoryServiceForOwnerData`, which scopes `get`, `list`, `delete`, `save`, `saveAll` and `deleteAll`; `TestResultService`'s CSV reads gate on the scoped `get`; both file readers refuse a name that escapes its directory; the per-service table is in `03-backend/security-and-tenancy.md`.
- **File:** `cms/src/main/java/ch/rupfizupfi/deck/api/services/FileMetadataService.java`
- **Change:** it hand-rolls the guard on every entry point (`get`, `list`, `save`, `saveAll`, `delete`, `deleteAll`, `connectToTestResult`) because `FileMetadata` is no `DataWithOwner`. Decide whether that rule can move behind a shared base class, or record it as the intended exception.
- **Also open:** `@RolesAllowed` on a `@BrowserCallable` method is enforced only over HTTP, so auditing roles needs a request-path test rather than an injected-bean one.

---

## Hardware

Moved to [`TASKS-hardware.md`](TASKS-hardware.md) — OQ-45, OQ-44, OQ-50,
OQ-43, OQ-74.

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

### [ ] OQ-34 · Delete the profile-picture feature
- **Scope:** the image column on `User` / `application_user`, its rows in `cms/src/main/resources/data.sql`, and any UI reading it.
- **Note:** with `ddl-auto=update` Hibernate will not drop the column — do it manually on the live database.

### [ ] OQ-56 · Define or drop the `rclone` service
- **Files:** `docker/docker-compose.yaml`, `docker/.env.example` (`COMPOSE_PROFILES`, now `deck` only)
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

