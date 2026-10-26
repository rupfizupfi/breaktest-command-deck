# Shared by both modules; MODULE selects which one is built and copied out.
# Build through compose, which passes MODULE per service:
#   docker compose -f docker/docker-compose.yaml --profile cms  up -d --build
#   docker compose -f docker/docker-compose.yaml --profile deck up -d --build

FROM gradle:9.8.0-jdk26-corretto AS build-image
ARG MODULE
COPY --chown=gradle:gradle . /home/gradle/src
WORKDIR /home/gradle/src

# Driver plugins are not build inputs; the native bench loads them at launch (doc/dev/backend/driver-jars.md).
RUN gradle clean :${MODULE}:bootJar --no-daemon -Pvaadin.productionMode=true

FROM eclipse-temurin:27-jre AS app-image
ARG MODULE

ARG UID=10001
RUN adduser \
    --disabled-password \
    --gecos "" \
    --home "/home/appuser" \
    --shell "/sbin/nologin" \
    --uid "${UID}" \
    appuser

# The docker profile serves HTTPS on 443; compose maps host 8043 onto it.
EXPOSE 443

# Matched by the -application suffix (archiveBaseName) so the -library jar in build/libs is not copied too.
COPY --chown=appuser:appuser --from=build-image \
     /home/gradle/src/${MODULE}/build/libs/*-application.jar /app/app.jar

COPY --chown=appuser:appuser cms/src/docker/bin/startup.sh /usr/local/bin/startup.sh
RUN chmod +x /usr/local/bin/startup.sh

USER appuser

ENTRYPOINT ["/usr/local/bin/startup.sh"]
CMD ["java", "-jar", "/app/app.jar"]
