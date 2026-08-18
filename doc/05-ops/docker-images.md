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

Because the build stage copies the whole repo and compiles from source,
`lib/usbmodbus.jar` must be present in the build context or the
`:command-deck` image fails to compile — see
[`../03-backend/hardware-integration.md`](../03-backend/hardware-integration.md).

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
