# One image definition for both modules. They differ only in which module is built and which jar is
# copied out, so MODULE carries that and there is no second file to keep in step — the two copies
# this replaced had already drifted apart, and the deck's extra directives all existed to deliver a
# driver plugin into an image that, being Linux, could never use one.
#
# Build through compose, which passes MODULE per service:
#   docker compose -f docker/docker-compose.yaml --profile cms  up -d --build
#   docker compose -f docker/docker-compose.yaml --profile deck up -d --build

FROM gradle:9.7.0-jdk26-corretto AS build-image
ARG MODULE
COPY --chown=gradle:gradle . /home/gradle/src
WORKDIR /home/gradle/src

# No credentials, no secret mounts: nothing in this build resolves anything from a private
# repository. Driver plugins are not build inputs in any deployment - the bench supplies them at
# launch from drivers/ (doc/03-backend/driver-jars.md) and the container does not use them at all.
RUN gradle clean :${MODULE}:bootJar --no-daemon -Pvaadin.productionMode=true

FROM eclipse-temurin:26-jre AS app-image
ARG MODULE

ARG UID=10001
RUN adduser \
    --disabled-password \
    --gecos "" \
    --home "/nonexistent" \
    --shell "/sbin/nologin" \
    --no-create-home \
    --uid "${UID}" \
    appuser

# The docker profile serves HTTPS on 443; compose maps host 8043 onto it.
EXPOSE 443

# Named by suffix, not by a *.jar glob: both modules set archiveBaseName so the boot jar is
# <module>-application.jar, and matching that exactly means a second jar appearing in build/libs
# (a plain `jar` task output, say) cannot turn this COPY into an ambiguous multi-source one.
COPY --chown=appuser:appuser --from=build-image \
     /home/gradle/src/${MODULE}/build/libs/*-application.jar /app/app.jar

# PropertiesLauncher is the boot jar's Main-Class (set in command-deck/build.gradle) and stays that
# way for both images: it is how the native bench extends the classpath with drivers/ at launch. No
# LOADER_PATH is set here, so in a container it simply extends nothing.
COPY --chown=appuser:appuser cms/src/docker/bin/startup.sh /usr/local/bin/startup.sh
RUN chmod +x /usr/local/bin/startup.sh

USER appuser

ENTRYPOINT ["/usr/local/bin/startup.sh"]
CMD ["java", "-jar", "/app/app.jar"]
