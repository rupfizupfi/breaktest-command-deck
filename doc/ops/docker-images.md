# Docker images

## Purpose

How the container image is built and what happens between container start and
the JVM. Where the images get deployed, and the compose/profile configuration
around them, is [`docker-and-profiles.md`](docker-and-profiles.md).

## One Dockerfile, two modules

`docker/Dockerfile` builds both images; `MODULE` is the only difference, and
compose supplies it per service:

```dockerfile
FROM gradle:9.7.1-jdk26-corretto AS build-image
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

The image carries no driver plugin (the Linux images cannot use the Windows-only
drivers, which refuse to register; the `deck` container is the simulation
deployment), so the build needs no credentials. The `cms` image is a supported
production deployment on the cloud host, so changes to this Dockerfile or
`startup.sh` can affect production.

* **The jar is matched by suffix, not a bare `*.jar` glob.** Both modules set
  `archiveBaseName`, so the boot jar is `<module>-application.jar`; a plain jar
  in `build/libs/` (after `assemble`, say) cannot make the `COPY` ambiguous.
* **`EXPOSE 443` is documentation only.** The container listens on `443`
  because `application-docker.properties` sets `server.port=${PORT:443}`;
  compose maps host `8043` to that.
* **The Dockerfile reads `startup.sh` out of the cms tree.** `:cms` is already a
  hard Gradle dependency of `:command-deck`, so one shared entrypoint matches the
  module relationship.
* **`PropertiesLauncher` is the boot jar's `Main-Class`** although no container
  sets `LOADER_PATH`: the native bench uses it to add `drivers/` at launch, and
  unset it adds nothing. One artefact serves both deployments.

## `startup.sh` — runtime fixups

`cms/src/docker/bin/startup.sh`, the entrypoint for both images, does two
things before `exec "$@"`:

1. If `/home/appuser/keystore/rupfizupfi.p12` is missing, generate a
   self-signed PKCS12 keystore via `keytool` with subject
   `CN=rupfizupfi.ch, OU=IT, O=Rupfizupfi, L=Bern, S=Bern, C=CH`, password
   the password resolved from `KEY_STORE_PASSWORD`, else
   `/run/secrets/keystore-password`, else `rupfizupfi.p12.password` beside the
   keystore. With none of them it generates a random password and saves it to
   that file (mode 600) so restarts reuse it. There is no default password: an
   existing keystore with no password available fails startup with a message.
   The resolved value is exported, so `server.ssl.key-store-password` picks it
   up. Self-signing is the intended operating mode — the tester is reached over
   a trusted local network.
2. Read `DB_PASSWORD_FILE` (the secret-mounted path) and re-export its
   contents as `DB_PASSWORD`, so the `${DB_PASSWORD}` placeholder in
   `application-docker.properties` resolves. One readability check covers unset
   and unreadable, and prints the value it got to tell them apart. The script runs under `set -eu`, so a failed `keytool`
   stops the container instead of starting one that cannot serve HTTPS.

## Where to look in the code

| Concern | File |
|---|---|
| Both images | `docker/Dockerfile` (`MODULE` build arg) |
| Which module each service builds | `docker/docker-compose.yaml` (`build.args`) |
| Shared entrypoint | `cms/src/docker/bin/startup.sh` |
| Jar naming | `cms/build.gradle`, `command-deck/build.gradle` |
