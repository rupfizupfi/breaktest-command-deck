FROM gradle:8.12.1-jdk23-corretto AS build-image
COPY --chown=gradle:gradle . /home/gradle/src
WORKDIR /home/gradle/src

RUN gradle clean :cms:bootJar --no-daemon -Pvaadin.productionMode=true

FROM eclipse-temurin:23-jre AS app-image

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
COPY --chown=appuser:appuser --from=build-image /home/gradle/src/cms/build/libs/*.jar /app/cms.jar

COPY --chown=appuser:appuser cms/src/docker/bin/startup.sh /usr/local/bin/startup.sh
RUN chmod +x /usr/local/bin/startup.sh

USER appuser

ENTRYPOINT ["/usr/local/bin/startup.sh"]
CMD ["java", "-jar", "/app/cms.jar"]

# ENTRYPOINT ["java", "-XX:+UnlockExperimentalVMOptions", "-XX:+UseCGroupMemoryLimitForHeap", "-Djava.security.egd=file:/dev/./urandom","-jar","/app/cms.jar"]