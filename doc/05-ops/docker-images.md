> Branch: `dev-split` — split out of `docker-and-profiles.md` 2026-08-17.

# Docker images

## Purpose

How the two container images are built and what happens between container
start and the JVM. Where those images get deployed, and the compose/profile
configuration around them, is
[`docker-and-profiles.md`](docker-and-profiles.md).

## Two-stage Dockerfile pattern

Both `cms/Dockerfile` and `command-deck/Dockerfile` follow the same shape:

```dockerfile
FROM gradle:9.7.0-jdk26-corretto AS build-image
COPY --chown=gradle:gradle . /home/gradle/src
WORKDIR /home/gradle/src
RUN gradle clean :<module>:bootJar --no-daemon -Pvaadin.productionMode=true

FROM eclipse-temurin:26-jre AS app-image
RUN adduser ... appuser
EXPOSE 8080
COPY --from=build-image /home/gradle/src/<module>/build/libs/*.jar /app/<module>.jar
COPY cms/src/docker/bin/startup.sh /usr/local/bin/startup.sh
ENTRYPOINT ["/usr/local/bin/startup.sh"]
CMD ["java", "-jar", "/app/<module>.jar"]
```

Three details that surprise people:

* **The `*.jar` glob is safe, but only because of the build command.** The
  build stage runs `:MODULE:bootJar`, and `bootJar` does not depend on `jar`,
  so `build/libs/` holds exactly one artefact at `COPY` time. Change that
  command to `assemble` or `build` and the glob matches two jars, breaking
  the image. See
  [`../02-modules/module-layout.md`](../02-modules/module-layout.md).
* **`EXPOSE 8080` is documentation only.** The container listens on `443`
  because `application-docker.properties` sets `server.port=${PORT:443}`;
  compose maps host `8043` to that.
* **The deck image reads `startup.sh` out of the cms tree.** Accepted as
  deliberate (2026-08-16) — `:cms` is already a hard Gradle dependency of
  `:command-deck`, so one shared entrypoint matches the module relationship.
  Anyone moving or deleting that file must rebuild both images.

### Where the deck image diverges: driver plugins

`command-deck/Dockerfile` adds a handful of directives the cms image has no use for, all
serving one rule — **the licence-restricted `usbmodbus.jar` is never in an
image**, and `lib/` is excluded by `.dockerignore` so it never even reaches the
build context:

| Addition | Why |
|---|---|
| `--mount=type=secret,id=github-token,required=false` on the build `RUN`, plus `:command-deck:stageDrivers` in the same command | Resolves the **public** `dscusb` driver plugin from GitHub Packages, which demands a token even for public reads. A secret mount keeps it out of every layer. Optional by design: no token means a warning and an empty `/app/drivers`, not a failed build. Needs BuildKit — the default in current Docker, but not in engines old enough to lack it. |
| `COPY --from=build-image .../build/drivers/ /app/drivers/` | The staged public plugin. Filenames keep their version, so `ls /app/drivers` in a running container identifies the driver build. Verified that an **empty** staging directory copies fine and yields an empty `/app/drivers` — that is what keeps an unreachable driver from failing the image build. |
| `RUN mkdir -p /app/drivers-local` | Mount point for the restricted plugin, supplied by the tester as a read-only bind mount. Empty is valid; the app then refuses to start in real mode and names what is missing. |
| `ENV LOADER_PATH=/app/drivers,/app/drivers-local` | `PropertiesLauncher` extends the classpath with these at launch. Earlier entries win on collisions. |

Consequence worth knowing: a driver is swapped by replacing a file and
restarting the container — no rebuild. Owned by
[`../03-backend/driver-jars.md`](../03-backend/driver-jars.md); build side in
[`../02-modules/gradle-build.md`](../02-modules/gradle-build.md#driver-plugins-loaderpath-not-the-classpath).

## `startup.sh` — runtime fixups

`cms/src/docker/bin/startup.sh`, the entrypoint for both images, does two
things before `exec "$@"`:

1. If `/home/appuser/keystore/rupfizupfi.p12` is missing, generate a
   self-signed PKCS12 keystore via `keytool` with subject
   `CN=rupfizupfi.ch, OU=IT, O=Rupfizupfi, L=Bern, S=Bern, C=CH`, password
   `KEY_STORE_PASSWORD` (default `changeit`). This is the **intended**
   operating mode, not a dev-only fallback — the tester is reached over a
   trusted local network.
2. Read `DB_PASSWORD_FILE` (the secret-mounted path) and re-export its
   contents as `DB_PASSWORD`, so the `${DB_PASSWORD}` placeholder in
   `application-docker.properties` resolves. The script exits non-zero if the
   variable is unset or the file is missing.

## Where to look in the code

| Concern | File |
|---|---|
| CMS image | `cms/Dockerfile` |
| Deck image | `command-deck/Dockerfile` |
| Shared entrypoint | `cms/src/docker/bin/startup.sh` |
| Jar naming | `cms/build.gradle`, `command-deck/build.gradle` |

## Open questions

None of its own. Related: OQ-61 (deck should reach the cloud Postgres) in
[`docker-and-profiles.md`](docker-and-profiles.md).
