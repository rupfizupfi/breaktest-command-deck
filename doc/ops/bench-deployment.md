# Running the deck natively on the bench

## Purpose

**This is how the deck is deployed.** The hardware controller is a Windows PC
and `command-deck` runs natively on it. Both driver plugins are Windows-only and
the `docker` image is Linux, so a containerised deck cannot drive the bench
([`dev/backend/driver-jars.md`](../dev/backend/driver-jars.md#both-drivers-are-windows-only-and-that-decides-the-deployment)).
A serial rewrite for a Linux host was declined and is a recorded future option in
[`../decisions.md`](../decisions.md#bench-platform-windows-native-owner-2026-08-30).
The `docker` deck profile is for **tests and simulations**, with no driver in the
image ([`docker-and-profiles.md`](docker-and-profiles.md)).

**There is no `bench` properties file.** What differs between a bench run and the
container — a keystore path, a JDBC URL, the hardware mode — is an environment
variable; a third properties file would be a near-duplicate per module, against
OQ-4. `spring.profiles.group.bench=docker` makes `SPRING_PROFILES_ACTIVE=bench`
activate `docker` as well.

**`bench` is also the simulation guard's marker.** `HardwareModeCheck` refuses a
simulator when `bench` is active, so a bench must be started as `bench` — which
`run-bench.ps1` always does — never as plain `docker`. See
[below](#why-simulation-cannot-happen-here-by-accident).

## Contents

- [What the launcher does](#what-the-launcher-does)
- [Environment](#environment)
- [Drivers](#drivers)
- [Why simulation cannot happen here by accident](#why-simulation-cannot-happen-here-by-accident)

## What the launcher does

`script/run-bench.ps1` is the bench equivalent of the container entrypoint
(`cms/src/docker/bin/startup.sh`) and mirrors it: self-sign a keystore if absent, read the database password from a file, then launch. It also
creates the drivers directory and prints the resolved database URL without
credentials, so a URL pointing at a reachable-but-wrong database is visible.

```powershell
$env:DB_URL = 'jdbc:postgresql://<cloud-host>:5432/rupfizupfi'
./script/run-bench.ps1 -DbPasswordFile C:\deck\.secrets\db-password.txt
```

Build the jar first — the launcher does not:

```powershell
./gradlew :command-deck:bootJar -Pvaadin.productionMode=true
```

## Environment

| Variable / parameter | Default | Notes |
|---|---|---|
| `DB_URL` | none — **required** | The cloud Postgres. No local fallback on the bench: a deck that quietly wrote results to the wrong database is worse than one that refuses to start (OQ-61). |
| `DB_PASSWORD_FILE` | none — **required** | File containing the password, matching the container's secret convention. |
| `KEY_STORE_PATH` | `%USERPROFILE%\keystore\rupfizupfi.p12` | Self-signed if absent — the intended operating mode on a trusted local network. |
| `KEY_STORE_PASSWORD` | none — resolved, see below | First hit wins: `-KeyStorePassword` / `KEY_STORE_PASSWORD`, then the file `<keystore>.password` beside the keystore (`rupfizupfi.p12.password`). Only when a **new** keystore is about to be created and neither exists, the launcher generates a random 24-character password and saves it to that file, readable by the current user only (`icacls`, inheritance removed), then self-signs with it. An existing keystore with no password available stops the launcher. There is no built-in default, as in `startup.sh`. |
| `LOADER_PATH` | set by the launcher from `-DriversPath` | Where the driver plugins are loaded from. |

**Upgrading from the old `changeit` default.** A keystore created by an earlier launcher has the password
`changeit` and no `.password` file, so the first start now stops with a message naming the sources. One-time fix:
either set `KEY_STORE_PASSWORD=changeit` for the launch, or write `changeit` into `%USERPROFILE%\keystore\rupfizupfi.p12.password`;
or delete the keystore and let the launcher regenerate it with a random password (clients then see a new certificate).

## Drivers

Put `dscusb.jar` and `usbmodbus.jar` in the launcher's `-DriversPath`
(`drivers/` by default). Both are needed: `deck.hardware.mode=real` requires both
provider beans and refuses startup naming whichever is missing — it never falls
back to a simulator.

Swapping a driver is a file copy and a restart. The boot jar contains none, and
each plugin verifies at startup that it was built against a compatible contract
version; see
[`dev/backend/driver-jars.md`](../dev/backend/driver-jars.md#the-jar-on-the-machine-is-checked-at-startup).

## Why simulation cannot happen here by accident

`HardwareModeCheck` allowlists the profiles a simulator may run under
(`SIMULATION_PROFILES` = `dev`, `docker`) rather than blocklisting the deployment
profile, and permits simulation only when **every** effective profile is on the
list. A bench run activates `bench` *and* `docker`; `bench` is not on the list, so
simulation is refused, while the container's plain `[docker]` may simulate. So is
`dev,bench`, and so is any profile not on the list.

`bench` is the marker that says this JVM can reach the machine.
`HardwareModeCheckTest` pins it, and fails if `bench` is added to the allowlist. Rationale:
[`dev/modules/spring-boot-setup.md`](../dev/modules/spring-boot-setup.md#hardware-mode).
