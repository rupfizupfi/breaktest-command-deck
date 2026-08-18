> Branch: `dev-split` — captured 2026-08-17.

# Runbook — common failure modes

## Purpose

When something fails, what's the fastest path to a fix? One problem ->
one diagnostic -> one fix per entry. No theory, no diagrams. Cross-link
to the deeper docs for the *why*.

> **No automated tests.** Per `CLAUDE.md`, this codebase has no test suite
> (`./gradlew check` skips a test task that does not exist). The two
> verification gates are `./script/typecheck.ps1` and the `/deck-run`
> smoke test; beyond those, verification is manual and this runbook is the
> primary safety net.

## Contents

- [Bring-up checklist (Docker)](#bring-up-checklist-docker)
- [Failure modes](#failure-modes)
  - [`Error response from daemon: ... db-password.txt: no such file or directory`](#error-response-from-daemon--db-passwordtxt-no-such-file-or-directory)
  - [App container exits immediately with `ERROR: DB_PASSWORD_FILE environment variable is not set` or `... secret file not found at /run/secrets/db-password`](#app-container-exits-immediately-with-error-db_password_file-environment-variable-is-not-set-or--secret-file-not-found-at-runsecretsdb-password)
  - [App container starts but TLS fails / browser shows `ERR_CONNECTION_RESET`](#app-container-starts-but-tls-fails--browser-shows-err_connection_reset)
  - [`Error starting userland proxy: listen tcp 0.0.0.0:8043: bind: address already in use`](#error-starting-userland-proxy-listen-tcp-00008043-bind-address-already-in-use)
  - [Both Compose profiles needed simultaneously](#both-compose-profiles-needed-simultaneously)
  - [Dev: `JdbcSQLNonTransientConnectionException: Database may be already in use`](#dev-jdbcsqlnontransientconnectionexception-database-may-be-already-in-use)
  - [Dev: app boots but tables are empty](#dev-app-boots-but-tables-are-empty)
  - [Dev: re-running creates duplicate-key violations from `data.sql`](#dev-re-running-creates-duplicate-key-violations-from-datasql)
  - [`:command-deck` refuses to start: "COMMAND DECK CANNOT START"](#command-deck-refuses-to-start-command-deck-cannot-start)
  - [Test starts but motor never moves / no measurements stream](#test-starts-but-motor-never-moves--no-measurements-stream)
  - [Relay never fires after a destructive test](#relay-never-fires-after-a-destructive-test)
  - [`LoadCellThread` writes empty CSVs](#loadcellthread-writes-empty-csvs)
  - [Frontend reports `User can only access their own data`](#frontend-reports-user-can-only-access-their-own-data)
  - [Frontend `404 /api/...` for upload/download endpoints](#frontend-404-api-for-uploaddownload-endpoints)
  - [Postgres container restarts in a loop with `database "rupfizupfi" does not exist`](#postgres-container-restarts-in-a-loop-with-database-rupfizupfi-does-not-exist)
  - [Gradle wrapper crashes with `Unsupported class file major version NN`](#gradle-wrapper-crashes-with-unsupported-class-file-major-version-nn)
  - [Vaadin dev-server complains about generated files](#vaadin-dev-server-complains-about-generated-files)
- [Where to look in the code](#where-to-look-in-the-code)
- [Open questions](#open-questions)

## Bring-up checklist (Docker)

Before `docker compose up -d` for the first time on a host:

1. `<repo>/.secrets/db-password.txt` exists with the desired Postgres
   password (one line, no trailing newline preferred).
2. `docker/.env` contains `KEY_STORE_PASSWORD=<some-value>`.
3. Optional: `docker/keystore/rupfizupfi.p12` is a real PKCS12 cert with
   alias `rupfizupfi` and the matching password. Otherwise the startup
   script self-signs one.
4. `docker/breaktester/` exists (will hold `settings.json`, uploads,
   CSV result files).
5. Pick a profile: `--profile deck` for the test-execution role, or
   `--profile cms` for the content-only role. They cannot run together
   on the same host (port `8043` collision).

See [`docker-and-profiles.md`](docker-and-profiles.md) for the full
environment.

## Failure modes

### `Error response from daemon: ... db-password.txt: no such file or directory`
- **Diagnostic:** `ls -la .secrets/`
- **Fix:** create `<repo>/.secrets/db-password.txt` with the Postgres
  password. Compose reads it as a Docker secret per
  `docker/docker-compose.yaml:69-71`.

### App container exits immediately with `ERROR: DB_PASSWORD_FILE environment variable is not set` or `... secret file not found at /run/secrets/db-password`
- **Diagnostic:** `docker compose logs server-deck` (or `server-cms`).
- **Fix:** the secret is declared in compose but the file is missing on
  the host — see previous entry. Compose silently mounts an empty path,
  `cms/src/docker/bin/startup.sh:26` then bails. Create the file and
  `docker compose up -d` again.

### App container starts but TLS fails / browser shows `ERR_CONNECTION_RESET`
- **Diagnostic:** `docker compose exec server-deck ls -la /home/appuser/keystore/`
- **Fix:** if `rupfizupfi.p12` is missing, the startup script auto-creates
  one with subject `CN=rupfizupfi.ch`. If it *exists* but the keystore
  password does not match `KEY_STORE_PASSWORD` in `docker/.env`, Spring
  Boot fails at TLS-init. Either align the password or delete the file
  and let the script regenerate. See
  [`docker-and-profiles.md`](docker-and-profiles.md).

### `Error starting userland proxy: listen tcp 0.0.0.0:8043: bind: address already in use`
- **Diagnostic:** Windows: `netstat -ano | findstr 8043`. Linux:
  `ss -lntp | grep 8043`.
- **Fix:** another process (or the *other* compose profile) holds the
  port. Stop the rival container (`docker compose --profile cms down`
  before bringing up `--profile deck`) or change the host-port mapping
  in `docker/docker-compose.yaml`.

### Both Compose profiles needed simultaneously
- **Cause:** you are on the wrong host. `cms` belongs on the cloud host,
  `deck` on the tester — they are separate deployments and share the
  `8043:443` mapping precisely because they never coexist. See
  [`docker-and-profiles.md`](docker-and-profiles.md).
- **Fix (if you really need both locally, e.g. to reproduce a bug):**
  override the host port for one of them with a compose override file
  rather than editing `docker-compose.yaml`.

### Dev: `JdbcSQLNonTransientConnectionException: Database may be already in use`
- **Diagnostic:** check that you are not already running another bootRun
  in another shell. `lsof .data/deck.mv.db.lock.db` (Linux/macOS) or
  Resource Monitor (Windows).
- **Fix:** stop the other process. Both `:cms:bootRun` and
  `:command-deck:bootRun` open `./.data/deck.mv.db` exclusively (see
  [`db.md`](db.md)). Run only one at a time, or edit
  `application-dev.properties` to use distinct files per module.

### Dev: app boots but tables are empty
- **Diagnostic:** `select count(*) from application_user` in the H2
  console (`http://localhost:8080/h2-console`).
- **Fix:** the seed is gated on `application_user` being empty, so a
  half-populated database never re-seeds. Delete `.data/` and boot again.
  Either module can seed — `data.sql` lives in cms but reaches
  `:command-deck` via the `cms-library` jar on its classpath, so
  `:command-deck:bootRun` alone against a fresh DB does seed. See
  [`db.md`](db.md).

### Dev: re-running creates duplicate-key violations from `data.sql`
- **Diagnostic:** `org.h2.jdbc.JdbcSQLIntegrityConstraintViolationException`
  on insert into `application_user` / `gear_type` / etc.
- **Fix:** the seed uses fixed IDs — Spring will re-execute it on each
  boot because `spring.sql.init.mode=always`. The errors are logged but
  the application still starts. To clean-slate: stop the app,
  `rm -rf .data/`, restart. See [`db.md`](db.md).

### `:command-deck` refuses to start: "COMMAND DECK CANNOT START"
The build succeeds without the driver jars; running does not. The message
names the missing jar.
- **Diagnostic:** `ls lib/` — both `dscusb.jar` and `usbmodbus.jar` must be
  there. The `drivers` source set is skipped unless **both** are present, so
  one missing jar disables *both* providers.
- **Fix (`usbmodbus.jar`):** gitignored (`.gitignore:36`), licence-restricted.
  Acquire it from the original author / private artefact store and drop it in
  `lib/`, then rebuild.
- **Fix (`dscusb.jar`):** **tracked** in git, so a fresh checkout has it. If
  missing, `git checkout -- lib/dscusb.jar`.
- **Not a fix:** there is no simulated fallback, deliberately — absent
  hardware must never look like working hardware. `deck.hardware.mode=simulated`
  is refused too, until the simulator exists.
  See [`spring-boot-setup.md`](../02-modules/spring-boot-setup.md#hardware-mode).

### Test starts but motor never moves / no measurements stream
- **Diagnostic:** `docker compose logs server-deck | grep -E 'openConnection|readData'`.
  Or check `/topic/load-cell` in the browser dev-tools network panel
  (filter `ws://`).
- **Fix:** USB device not connected or not enumerated. Linux: ensure the
  appuser inside the container has access — typically requires running
  the container with `--device=/dev/ttyUSB0` or similar (not currently
  in `docker-compose.yaml`). Windows: usbipd-attach. See
  [`hardware-integration.md`](../03-backend/hardware-integration.md).
  There is no startup check for hardware presence — flagged as an open
  question.

### Relay never fires after a destructive test
- **Diagnostic:** look for `Four way switch com port not found` in logs.
- **Fix:** `FourWayRelaySwitch.getComPort()` searches for a serial port
  whose descriptive name contains `CH9102`. If your relay board uses a
  different USB-serial chipset, the port enumeration loop dumps every
  detected port (`FourWayRelaySwitch.java:24-29`). Patch the substring
  match.

### `LoadCellThread` writes empty CSVs
- **Diagnostic:** check the CSV path returned by
  `CSVStoreService.generateFilePathForTestResult`. Verify the
  result-data location from `SettingService` (`FILE_RESULT_DATA`).
- **Fix:** `FileSystemCheck` should have refused to start the test if
  the directory is missing, but only if the setting is non-null. If the
  setting was deleted, `StorageLocationService.getResultDataLocation()`
  returns `null` and the check fails with `Result data location is not
  set`. Restore the setting in System -> Settings (cms UI) or by editing
  `~/breaktester/settings.json`. See
  [`test-execution-engine.md`](../03-backend/test-execution-engine.md).

### Frontend reports `User can only access their own data`
- **Diagnostic:** browser console, the `SecurityException` propagates
  back via Hilla.
- **Fix:** the user is non-admin and is trying to read/write a row owned
  by someone else. Either change the row's `owner_id` (admin-only
  operation in `UserService`) or grant the user `ROLE_ADMIN`. See
  [`security-and-tenancy.md`](../03-backend/security-and-tenancy.md).

### Frontend `404 /api/...` for upload/download endpoints
- **Diagnostic:** check `application.properties:16`
  (`vaadin.exclude-urls=/api/**`) is intact.
- **Fix:** without this exclusion Vaadin's router intercepts `/api/...`
  and returns the SPA shell. Restore the property and rebuild.

### Postgres container restarts in a loop with `database "rupfizupfi" does not exist`
- **Diagnostic:** `docker compose logs db`
- **Fix:** the named volume `db-data` was initialised under different
  `POSTGRES_DB` / `POSTGRES_USER`. `docker compose down -v` (warning:
  destroys data) and bring it back up with the env values matching the
  current compose file.

### Gradle wrapper crashes with `Unsupported class file major version NN`
- **Diagnostic:** `java -version`
- **Fix:** the build needs **JDK 26** — the Gradle toolchain pins
  `languageVersion 26` and the Docker build image is
  `gradle:9.7.0-jdk26-corretto`. An older or newer JDK on PATH can produce
  a class-file version Gradle's Groovy parser rejects. Use a version
  manager to select JDK 26 for the build.

### Vaadin dev-server complains about generated files
- **Diagnostic:** check `git status` for stale `generated/` files.
- **Fix:** `src/main/frontend/generated/` is gitignored but parts are
  tracked from earlier commits, so a Hilla regeneration produces noisy
  diffs. `git checkout -- '*/src/main/frontend/generated/*'` resets to the
  tracked baseline. The decision is to untrack these trees entirely
  (OQ-14) — once that lands, this failure mode disappears.

## Where to look in the code

| Concern | File |
|---|---|
| Compose | `docker/docker-compose.yaml` |
| Compose env | `docker/.env` |
| Container init | `cms/src/docker/bin/startup.sh` |
| Local-JAR wiring | `command-deck/build.gradle` |
| Profile properties | `cms/src/main/resources/application-{dev,docker}.properties` |
| Vaadin URL exclusion | `cms/src/main/resources/application.properties:16` |

## Open questions

The runbook is reactive; for the strategic gaps see
[`docker-and-profiles.md`](docker-and-profiles.md),
[`db.md`](db.md), and the per-doc "open questions" sections under
`03-backend/`.
