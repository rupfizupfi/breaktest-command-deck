# Runbook — common failure modes

## Purpose

One problem -> one diagnostic -> one fix per entry. The *why* lives in the
linked docs.

> **Only `/deck-run` exercises the running app.** The verification gates are `./gradlew :cms:test :command-deck:test` (unit + Testcontainers +
> context tests), `pnpm --dir cms test` (frontend logic),
> `./script/typecheck.ps1`, and the `/deck-run` smoke test; beyond those,
> verification is manual and this runbook is the primary safety net.

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
  - [Docker: nobody can log in after the first start](#docker-nobody-can-log-in-after-the-first-start)
  - [`:command-deck` refuses to start: "COMMAND DECK CANNOT START"](#command-deck-refuses-to-start-command-deck-cannot-start)
  - [Test starts but motor never moves / no measurements stream](#test-starts-but-motor-never-moves--no-measurements-stream)
  - [Relay never fires after a destructive test](#relay-never-fires-after-a-destructive-test)
  - [`MeasurementLoop` writes empty CSVs](#measurementloop-writes-empty-csvs)
  - [Frontend reports `User can only access their own data`](#frontend-reports-user-can-only-access-their-own-data)
  - [Frontend `404 /api/...` for upload/download endpoints](#frontend-404-api-for-uploaddownload-endpoints)
  - [Postgres container restarts in a loop with `database "rupfizupfi" does not exist`](#postgres-container-restarts-in-a-loop-with-database-rupfizupfi-does-not-exist)
  - [Gradle wrapper crashes with `Unsupported class file major version NN`](#gradle-wrapper-crashes-with-unsupported-class-file-major-version-nn)
- [Where to look in the code](#where-to-look-in-the-code)

## Bring-up checklist (Docker)

Before `docker compose up -d` for the first time on a host:

1. `<repo>/.secrets/db-password.txt` exists with the desired Postgres
   password (one line, no trailing newline preferred).
2. `docker/.env` exists (copy `docker/.env.example`; it is gitignored,
   per host) and, only for a real cert, contains `KEY_STORE_PASSWORD=<its password>`
   (unset: the self-signed keystore gets a generated password).
3. Optional: `docker/keystore/rupfizupfi.p12` is a real PKCS12 cert with
   alias `rupfizupfi` and the matching password. Otherwise the startup
   script self-signs one.
4. `docker/breaktester/` exists (will hold `settings.json`, uploads,
   CSV result files).
5. Pick a profile: `--profile deck` for tests and simulations, or
   `--profile cms` for the cloud content host (a supported production
   deployment; treat changes to compose, `startup.sh` or the keystore as
   production-affecting). They cannot run together
   on the same host (port `8043` collision).

See [`docker-and-profiles.md`](docker-and-profiles.md) for the full
environment.

## Upgrade and deploy notes (cms in production)

- **Keystore password (breaking for an existing keystore).** `startup.sh` no
  longer falls back to `changeit`. A host whose `docker/keystore/rupfizupfi.p12`
  already exists and has no `KEY_STORE_PASSWORD`, `/run/secrets/keystore-password`
  or `rupfizupfi.p12.password` file now refuses to start. Before deploying this
  change, set `KEY_STORE_PASSWORD=changeit` once in `docker/.env` (if the
  keystore still uses the old default) or regenerate the keystore.
- **Postgres image** is pinned to `postgres:18.6-trixie`, matching production. The
  `db-data` mount is `/var/lib/postgresql` (required since 18). A major bump needs
  `pg_upgrade` or a dump/restore; see [`db.md`](db.md).

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
  Boot fails at TLS-init; with no password available at all the script
  itself exits with an error. Either align the password or delete the file
  (and `rupfizupfi.p12.password`) and let the script regenerate. See
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
  `deck` on a test/simulation host — separate deployments sharing the
  `8043:443` mapping because they never coexist. See
  [`docker-and-profiles.md`](docker-and-profiles.md).
- **Fix (if you need both locally, e.g. to reproduce a bug):**
  override the host port for one of them with a compose override file
  rather than editing `docker-compose.yaml`.

### Dev: `JdbcSQLNonTransientConnectionException: Database may be already in use`
- **Diagnostic:** another `bootRun` in another shell? `lsof .data/deck.mv.db.lock.db` (Linux/macOS) or
  Resource Monitor (Windows).
- **Fix:** stop the other process. Both `:cms:bootRun` and
  `:command-deck:bootRun` open `./.data/deck.mv.db` exclusively (see
  [`db.md`](db.md)). Run only one at a time, or edit
  `application-dev.properties` to use distinct files per module.

### Dev: app boots but tables are empty
- **Diagnostic:** `select count(*) from application_user` in the H2
  console (`http://localhost:8080/h2-console`).
- **Fix:** reference data is a migration that runs once, and
  `DevUserSeeder` fills only an empty user table, so a table emptied later
  stays empty. Delete `.data/` and boot again; either module migrates and
  seeds a fresh DB. See [`db.md`](db.md#seed-data).

### Docker: nobody can log in after the first start
- **Diagnostic:** the log shows `No ADMIN user exists and none was created`.
- **Fix:** no account is seeded outside `dev`. Set `DECK_ADMIN_PASSWORD` in
  `docker/.env` (or mount `/run/secrets/admin-password`) and restart;
  `InitialAdminBootstrap` creates `admin`. See [`docker-and-profiles.md`](docker-and-profiles.md).

### `:command-deck` refuses to start: "COMMAND DECK CANNOT START"
The build succeeds without the driver jars; running does not. The message
names the missing jar.
- **Diagnostic:** the error names each missing provider and its jar, and each
  jar registers independently — one missing jar disables only its own provider.
  `ls drivers/` on the bench machine: every launch path reads that one directory,
  so there is no build option to check as well.
- **In a container this is a misconfiguration, not a missing file.** The deck
  container is the simulation and test deployment and carries no driver.
  Compose sets `DECK_HARDWARE_MODE=simulated`; seeing this message there means
  something overrode it. Fix the mode, not the jars.
- **Fix (bench or dev machine):** put both jars in `drivers/`, then either
  `script/run-bench.ps1` (which sets `LOADER_PATH` itself) or restart `bootRun`
  with `--args='--deck.hardware.mode=real'`. Neither jar is in git; both are built from sibling repos
  ([`driver-jars.md`](../dev/backend/driver-jars.md)). Confirm the jars themselves
  before blaming the app: `./gradlew :command-deck:driverPluginTest`.
- **Fix (a jar is present and it still refuses):** the jar may be stale — a
  driver built against an incompatible `device-api` refuses to register, and one
  built before the skew check existed loads without verifying anything. `driverPluginTest` names which jar and why; the fix is a driver
  rebuild (`./gradlew shadowJar` in the sibling repo) and a file copy.
- **Not a fix:** there is no simulated fallback — absent hardware must never
  look like working hardware — and `deck.hardware.mode=simulated` is refused
  under the `bench` profile.
  See [`spring-boot-setup.md`](../dev/modules/spring-boot-setup.md#hardware-mode).

### Test starts but motor never moves / no measurements stream
- **Diagnostic:** the bench console log (`run-bench.ps1` output), filtered for
  `openConnection|readData`. Or check `/topic/load-cell` in the browser
  dev-tools network panel (filter `wss://`).
- **Fix:** USB device not connected or not enumerated on the bench PC. Hardware
  is only reachable from the native Windows bench, never from a container
  ([`bench-deployment.md`](bench-deployment.md)). `LoadCellCheck` and
  `FrequencyInverterCheck` normally refuse the run first — see
  [`hardware-integration.md`](../dev/backend/hardware-integration.md#pre-test-checks).

### Relay never fires after a destructive test
- **Diagnostic:** look for `Four way switch com port matching '...' not found`
  in logs; the `available port:` warnings above it list every detected port.
- **Fix:** `FourWayRelaySwitch` matches a serial port whose descriptive name
  contains `device.relay.port-description` (`CH9102` in command-deck
  `application.properties`). For a different USB-serial chipset, set that
  property to a substring of the listed name.

### `MeasurementLoop` writes empty CSVs
- **Diagnostic:** check the CSV path returned by
  `CSVStoreService.generateFilePathForTestResult`. Verify the
  result-data location from `SettingService` (`FILE_RESULT_DATA`).
- **Fix:** `FileSystemCheck` should have refused to start the test if
  the directory is missing, but only if the setting is non-null. If the
  setting was deleted, `StorageLocationService.getResultDataLocation()`
  returns `null` and the check fails with `Result data location is not
  set`. Restore the setting in System -> Settings (cms UI) or by editing
  `~/breaktester/settings.json`. See
  [`test-execution-engine.md`](../dev/backend/test-execution-engine.md).

### Frontend reports `User can only access their own data`
- **Diagnostic:** browser console, the `SecurityException` propagates
  back via Hilla.
- **Fix:** the user is non-admin and is trying to read/write a row owned
  by someone else. Either change the row's `owner_id` (admin-only
  operation in `UserService`) or grant the user `ROLE_ADMIN`. See
  [`security-and-tenancy.md`](../dev/backend/security-and-tenancy.md).

### Frontend `404 /api/...` for upload/download endpoints
- **Diagnostic:** check `application.properties:18`
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
  `gradle:9.7.1-jdk26-corretto`. An older or newer JDK on PATH can produce
  a class-file version Gradle's Groovy parser rejects. Use a version
  manager to select JDK 26 for the build.

## Where to look in the code

| Concern | File |
|---|---|
| Compose | `docker/docker-compose.yaml` |
| Compose env | `docker/.env` (from `docker/.env.example`) |
| Container init | `cms/src/docker/bin/startup.sh` |
| Driver plugin wiring | `command-deck/build.gradle` |
| Profile properties | `cms/src/main/resources/application-{dev,docker}.properties` |
| Vaadin URL exclusion | `cms/src/main/resources/application.properties:18` |
