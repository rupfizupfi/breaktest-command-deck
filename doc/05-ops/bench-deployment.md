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
The `docker` deck profile is not retired, it has a different job: **tests and
simulations**, with no driver in the image at all. This page owns the bench path;
that one is [`docker-and-profiles.md`](docker-and-profiles.md).

**There is no `bench` properties file, deliberately.** Everything that differs
between a bench run and the containerised deployment is an environment variable —
a keystore path, a JDBC URL, a storage root, and the hardware mode. A third
properties file would have been a 95 % duplicate per module, against OQ-4's
decision to *delete* duplicate properties files.

Instead `spring.profiles.group.bench=docker` gives the honest operator-facing
name for one line and no new file: `SPRING_PROFILES_ACTIVE=bench` activates
`docker` as well.

**`bench` is not only a nicer name — it is the simulation guard's marker.** The
two deployments differ on exactly one dangerous axis, `deck.hardware.mode`, so
`application-docker.properties` declares it for neither and each supplies its own
`DECK_HARDWARE_MODE` (an environment variable outranks every properties file;
unset, `application.properties` supplies the fail-safe `real`). `HardwareModeCheck`
permits simulation under `docker` but not under `bench`, and since a bench run's
active set is `[bench, docker]` while the container's is just `[docker]`, that one
extra profile is what refuses a simulator on the machine. It follows that a bench
must be started as `bench` — which `run-bench.ps1` always does — and never as
`docker`.

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
(`SIMULATION_PROFILES` = `dev`, `docker`) rather than blocklisting the deployment
profile, and permits simulation only when **every** effective profile is on the
list. A bench run activates `bench` *and* `docker`; `bench` is not on the list, so
simulation is refused — even though `docker` alone would be permitted, which is
what lets the deck container simulate. So is `dev,bench`, and so is any future
profile until it is added deliberately.

That is why the launcher sets `SPRING_PROFILES_ACTIVE=bench` and why starting a
bench as plain `docker` is wrong: `bench` is the marker that says this JVM can
reach the machine. `HardwareModeCheckTest` pins it, and would fail if `bench` were
ever added to the allowlist. Rationale:
[`../02-modules/spring-boot-setup.md`](../02-modules/spring-boot-setup.md#hardware-mode).
