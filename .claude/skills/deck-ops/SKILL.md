---
name: deck-ops
description: Runs, builds, and deploys the cms and command-deck modules — local dev with the shared H2/port constraints, production builds, and Docker Compose deployment with PostgreSQL and SSL. Use when starting the app, running bootRun, debugging startup failures, building jars or Docker images, deploying, editing Dockerfiles/compose files, or troubleshooting dev or production environments.
---

# Running and deploying

Sibling skills: `deck-run` *drives* a running app headlessly (boot-or-detect, screenshots, CRUD smoke — prefer its `node driver.mjs up` over hand-rolling a boot-and-poll); this skill owns the manual run/build/deploy knowledge and its footguns.

Route to the right reference — read only what the task needs:

| Task | Read |
|---|---|
| Local dev: bootRun, H2, login, startup failures, hot reload | [references/local-dev.md](references/local-dev.md) |
| Docker: compose profiles, secrets, keystore, docker Spring profile | [references/docker.md](references/docker.md) |

## Quick commands

```bash
./gradlew :command-deck:bootRun                                  # dev (includes all cms views/services)
./gradlew :cms:bootRun                                           # dev, cms only — never together with the deck
./gradlew :command-deck:bootJar -Pvaadin.productionMode=true     # production jar (flag required)
docker compose -f docker/docker-compose.yaml --profile deck up -d --build
```

## Facts that always apply

- **Never run both modules at once**: they share port 8080 AND the H2 file `jdbc:h2:file:./.data/deck` (file lock).
- **Fresh clone doesn't compile**: `lib/usbmodbus.jar` is gitignored and not in the repo — get it manually into `lib/`. `lib/dscusb.jar` is tracked.
- Each module builds TWO jars (`*-application.jar` + `*-library.jar`) — run/deploy the `-application` one.
- Known production issue: real-time WebSocket data is dev-only — `StatusService.ts` hardcodes `ws://localhost:8080/status`.
- Deep docs: `doc/05-ops/runbook.md`, `doc/05-ops/docker-and-profiles.md`, `doc/05-ops/docker-images.md`.
