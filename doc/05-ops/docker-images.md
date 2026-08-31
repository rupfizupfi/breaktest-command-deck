> Branch: `dev-split` — split out of `docker-and-profiles.md` 2026-08-17.

# Docker images

## Purpose

How the container image is built and what happens between container start and
the JVM. Where the images get deployed, and the compose/profile configuration
around them, is [`docker-and-profiles.md`](docker-and-profiles.md).

## One Dockerfile, two modules

`docker/Dockerfile` builds both images; `MODULE` is the only difference, and
compose supplies it per service:

```dockerfile
FROM gradle:9.7.0-jdk26-corretto AS build-image
ARG MODULE
COPY --chown=gradle:gradle . /home/gradle/src
WORKDIR /home/gradle/src
RUN gradle clean :${MODULE}:bootJar --no-daemon -Pvaadin.productionMode=true

FROM eclipse-temurin:26-jre AS app-image
ARG MODULE
RUN adduser ... appuser
EXPOSE 443
COPY --from=build-image /home/gradle/src/${MODULE}/build/libs/*-application.jar /app/app.jar
COPY cms/src/docker/bin/startup.sh /usr/local/bin/startup.sh
ENTRYPOINT ["/usr/local/bin/startup.sh"]
CMD ["java", "-jar", "/app/app.jar"]
```

**There were two copies of this until 2026-08-31.** They were identical except for
the module name plus a block of deck-only directives that existed solely to deliver
a driver plugin into the image — and since the deck container is a simulation
deployment on Linux, where both Windows-only drivers refuse to register, that block
delivered something unusable. Removing it made the two files identical, so they
became one. What went with it: a BuildKit secret mount for a GitHub Packages
token, `ARG GITHUB_ACTOR`, `:command-deck:stageDrivers`, a `COPY` of the staged
plugin, `mkdir /app/drivers-local`, and `ENV LOADER_PATH`. **The image build now
needs no credentials at all.**

Details worth knowing:

* **The jar is matched by suffix, not by a bare `*.jar` glob.** Both modules set
  `archiveBaseName`, so the boot jar is `<module>-application.jar`. The old glob was
  safe only because `bootJar` does not depend on `jar`, leaving one artefact in
  `build/libs/` — switch the build command to `assemble` and it matched two and
  broke. Matching the suffix removes that trap.
* **`EXPOSE 443` is documentation only**, and it used to say `8080`. The container
  listens on `443` because `application-docker.properties` sets
  `server.port=${PORT:443}`; compose maps host `8043` to that.
* **The Dockerfile reads `startup.sh` out of the cms tree.** Accepted as deliberate
  (2026-08-16) — `:cms` is already a hard Gradle dependency of `:command-deck`, so
  one shared entrypoint matches the module relationship.
* **`PropertiesLauncher` stays the boot jar's `Main-Class`** even though no
  container sets `LOADER_PATH`. It is how the native bench extends the classpath
  with `drivers/` at launch, and with the variable unset it extends nothing. One
  artefact serves both deployments.

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
   `application-docker.properties` resolves. One readability check covers both
   failures — unset and unreadable — and prints the value it got, so the message
   still tells them apart. The script runs under `set -eu`, so a failed `keytool`
   stops the container instead of starting one that cannot serve HTTPS.

## Where to look in the code

| Concern | File |
|---|---|
| Both images | `docker/Dockerfile` (`MODULE` build arg) |
| Which module each service builds | `docker/docker-compose.yaml` (`build.args`) |
| Shared entrypoint | `cms/src/docker/bin/startup.sh` |
| Jar naming | `cms/build.gradle`, `command-deck/build.gradle` |

## Open questions

None of its own. Related: OQ-61 (the *bench* should reach the cloud Postgres) in
[`docker-and-profiles.md`](docker-and-profiles.md).
