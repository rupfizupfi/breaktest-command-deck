# Docker deployment

Compose project name: `rupfizupfi`.

## Commands

```bash
docker compose -f docker/docker-compose.yaml --profile deck up -d --build
docker compose -f docker/docker-compose.yaml --profile cms up -d --build
```

Profiles: `deck` (service `server-deck`) and `cms` (service `server-cms`); the postgres `db` service always starts (healthcheck-gated). `docker/.env` sets `COMPOSE_PROFILES=deck,rclone` — note `rclone` is **not defined** in the compose file (stale).

## Prerequisites

- `docker/.secrets/db-password.txt` — mounted as the `db-password` secret; `startup.sh` exports it as `DB_PASSWORD`.
- `docker/keystore/` — PKCS12 cert dir mounted to `/home/appuser/keystore`. If missing, `cms/src/docker/bin/startup.sh` (shared entrypoint for both images) **auto-generates a self-signed keystore**. Password from `KEY_STORE_PASSWORD` in `docker/.env` (default `changeit`).
- `lib/usbmodbus.jar` must exist locally — the image build runs Gradle and fails without it.

## How the images build

Two-stage Dockerfiles per module: `gradle:9.7.0-jdk26-corretto` builds with

```
gradle clean :<module>:bootJar --no-daemon -Pvaadin.productionMode=true
```

then `eclipse-temurin:26-jre` runs as uid 10001 (`appuser`).

Known staleness (don't be misled):
- `COPY .../build/libs/*.jar` — the glob matches **two** jars (`-application` bootJar and `-library` plain jar); if the copy behaves oddly, that's why.
- `EXPOSE 8080` is stale — the `docker` profile binds **443** (HTTPS). Host port **8043 → container 443**.

## docker profile (Spring)

`SPRING_PROFILES_ACTIVE=docker` → PostgreSQL at `db:5432/rupfizupfi`, port 443 with the PKCS12 keystore, credentials from env/secret. App state persists in the `./breaktester` bind mount (`/home/appuser/breaktester`).

## Verifying a deploy

1. `docker compose -f docker/docker-compose.yaml ps` — `db` healthy, app service up.
2. `docker compose -f docker/docker-compose.yaml logs -f server-deck` — look for Spring startup on 443.
3. Open `https://<host>:8043` (self-signed cert warning is expected unless a real cert is in `docker/keystore/`).
4. Known issue: real-time WebSocket data does not work in this profile — the frontend `StatusService.ts` hardcodes `ws://localhost:8080/status` (needs `wss://<host>` to work in production).

## Hardware note

The deck container needs access to USB/serial devices to actually drive hardware — this is not configured in the compose file (no `devices:` mapping). CRUD and UI work regardless.
