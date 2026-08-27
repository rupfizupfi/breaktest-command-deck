FROM gradle:9.7.0-jdk26-corretto AS build-image
COPY --chown=gradle:gradle . /home/gradle/src
WORKDIR /home/gradle/src

# stageDrivers fetches the PUBLIC driver plugin (dscusb) from GitHub Packages, which demands a token
# even for public reads. The token is a BuildKit secret so it never lands in a layer; the actor name
# is not sensitive and rides in as a build arg.
#
# Both are optional, and the build succeeds without them: a missing driver must stay a *startup*
# failure named by HardwareModeCheck, never a build failure. An unreachable artifact repository is
# not a reason to be unable to build the application. stageDrivers warns loudly and leaves
# /app/drivers empty; the container then refuses to start in real mode and names what is missing.
# The licence-restricted usbmodbus.jar is never staged here at all - see below.
ARG GITHUB_ACTOR=docker-build
RUN --mount=type=secret,id=github-token,required=false \
    export GITHUB_ACTOR="$GITHUB_ACTOR" \
    && export GITHUB_TOKEN="$(cat /run/secrets/github-token 2>/dev/null || true)" \
    && gradle clean :command-deck:bootJar :command-deck:stageDrivers \
       --no-daemon -Pvaadin.productionMode=true

FROM eclipse-temurin:26-jre AS app-image

ARG UID=10001
RUN adduser \
    --disabled-password \
    --gecos "" \
    --home "/nonexistent" \
    --shell "/sbin/nologin" \
    --no-create-home \
    --uid "${UID}" \
    appuser


EXPOSE 8080
COPY --chown=appuser:appuser --from=build-image /home/gradle/src/command-deck/build/libs/*.jar /app/command-deck.jar

# The public driver plugin, resolved by version at image build time. The jar keeps its version in
# the filename so `ls /app/drivers` in a running container answers which driver build this is.
COPY --chown=appuser:appuser --from=build-image /home/gradle/src/command-deck/build/drivers/ /app/drivers/

# Mount point for the licence-restricted usbmodbus.jar, which may not be redistributed and is
# therefore never baked into an image. The tester supplies it by bind-mounting
# docker/drivers-local/ here; see docker/docker-compose.yaml. Empty is a valid state - the app then
# refuses to start in real mode and names the missing provider.
RUN mkdir -p /app/drivers-local && chown appuser:appuser /app/drivers-local

# The boot jar runs through PropertiesLauncher (set in command-deck/build.gradle), which extends the
# classpath with these directories at launch. Earlier entries win on class collisions, which is why
# /app/drivers-local must never hold a second copy of dscusb.
ENV LOADER_PATH=/app/drivers,/app/drivers-local

COPY --chown=appuser:appuser cms/src/docker/bin/startup.sh /usr/local/bin/startup.sh
RUN chmod +x /usr/local/bin/startup.sh

USER appuser

ENTRYPOINT ["/usr/local/bin/startup.sh"]
CMD ["java", "-jar", "/app/command-deck.jar"]
