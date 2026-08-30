> Branch: `feat/driver-plugins` — captured 2026-08-29.

# Running the deck natively on the bench

## Purpose

**This is how the deck is deployed** — decided, not a stopgap. The hardware
controller is a Windows PC and `command-deck` runs natively on it. Both driver
plugins are Windows-only and the `docker` image is Linux, so a containerised deck
cannot drive the bench at all
([`../03-backend/driver-jars.md`](../03-backend/driver-jars.md#both-drivers-are-windows-only-and-that-decides-the-deployment));
the alternative — rewriting both drivers onto serial so a Linux host could do the
job — was weighed and declined, and survives only as a recorded future option in
[`../06-feature-work/dscusb-serial-port/README.md`](../06-feature-work/dscusb-serial-port/README.md).
What remains of OQ-79 is only whether the unusable `docker` deck profile is
retired. This page owns the bench path; the containerised deployment is
[`docker-and-profiles.md`](docker-and-profiles.md).

**There is no `bench` properties file, deliberately.** On the axes that matter —
hardware mode and database — a bench run is identical to the containerised
deployment; only a keystore path, a JDBC URL and a storage root differ, and all
three are environment variables. A third profile would have been a 95 % duplicate
per module, against OQ-4's decision to *delete* duplicate properties files, and a
second name for the simulation guard to remember.

Instead `spring.profiles.group.bench=docker` gives the honest operator-facing
name for one line and no new file: `SPRING_PROFILES_ACTIVE=bench` activates
`docker` as well.

## Contents

- [What the launcher does](#what-the-launcher-does)
- [Environment](#environment)
- [Drivers](#drivers)
- [Why simulation cannot happen here by accident](#why-simulation-cannot-happen-here-by-accident)

## What the launcher does

`script/run-bench.ps1` is the bench equivalent of the container entrypoint
(`cms/src/docker/bin/startup.sh`) and mirrors it deliberately: self-sign a
keystore if absent, read the database password from a file, then launch. It also
creates the drivers directory and prints the resolved database URL without
credentials, so a typo pointing at a reachable-but-wrong database is visible
rather than silent.

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
| `KEY_STORE_PATH` | `%USERPROFILE%\keystore\rupfizupfi.p12` | Self-signed if absent — the intended operating mode on a trusted local network, not a fallback. |
| `KEY_STORE_PASSWORD` | `changeit` | Matches the auto-generated keystore. |
| `DECK_STORAGE_ROOT` | `%USERPROFILE%` | What `~` in the stored paths resolves to, i.e. the parent of `breaktester/`. |
| `LOADER_PATH` | set by the launcher from `-DriversPath` | Where the driver plugins are loaded from. |

## Drivers

Put `dscusb.jar` and `usbmodbus.jar` in the launcher's `-DriversPath`
(`drivers/` by default). Both are needed: `deck.hardware.mode=real` requires both
provider beans and refuses startup naming whichever is missing — it never falls
back to a simulator.

Swapping a driver is a file copy and a restart. The boot jar contains none, and
each plugin verifies at startup that it was built against a compatible contract
version; see
[`../03-backend/driver-jars.md`](../03-backend/driver-jars.md#the-jar-on-the-machine-is-checked-at-startup).

## Why simulation cannot happen here by accident

`HardwareModeCheck` allowlists the profiles a simulator may run under
(`SIMULATION_PROFILES`, currently just `dev`) rather than blocklisting the
deployment profile. A bench run activates `bench`/`docker`, so simulation is
refused; so is `dev,bench`, and so is any future profile until it is added to the
allowlist deliberately. That inversion is what makes a profile-less native
deployment safe — the old blocklist would simply have gone quiet. Rationale:
[`../02-modules/spring-boot-setup.md`](../02-modules/spring-boot-setup.md#hardware-mode).
