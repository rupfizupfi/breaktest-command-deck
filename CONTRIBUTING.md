# Contributing

Breaktest Command Deck is two Spring Boot + Hilla apps (`cms`, `command-deck`) plus the
`device-api` hardware contract. Start with [`doc/README.md`](doc/README.md) for the reading
order and which doc owns which topic.

## Before you change code

- Never edit `*/src/main/frontend/generated/` or `vite.generated.ts` — the build regenerates them.
- No driver jar is ever committed or built in; `drivers/` is a runtime plugin directory
  ([`drivers/README.md`](drivers/README.md), [`doc/dev/backend/driver-jars.md`](doc/dev/backend/driver-jars.md)).
- A new or changed JPA entity needs a Flyway migration in
  `cms/src/main/resources/db/migration/` — the schema is validated, never generated
  ([`doc/ops/db.md`](doc/ops/db.md)).

## Verification gates

Run the ones your change touches before you call it done. CI runs `_check-inventories.py`, both
Vitest suites, the full build (JVM tests included) and the typecheck on every pull request and
push to `main`; `driverPluginTest` needs vendor jars and stays local.

```bash
./gradlew :cms:test :command-deck:test     # JVM tests
pnpm --dir cms test                         # frontend logic tests (also: pnpm --dir command-deck test)
./script/typecheck.ps1                      # regenerates the Hilla client, then tsc --noEmit
./gradlew :command-deck:driverPluginTest    # the driver jars in drivers/ (Windows, after copying a jar in)
```

After any change under `doc/`, run the four doc gates from the repo root — see
[`doc/dev/conventions.md`](doc/dev/conventions.md), which also holds the doc standards.

## Commits

- One concern per branch and per commit.
- Commit messages are one line, `type: summary`, with `type` one of `feat`, `fix`, `chore`,
  `docs`, `refactor`, `test`, `wip`. No body, no trailers.
- Decisions with their rejected alternatives go in [`doc/decisions.md`](doc/decisions.md);
  open questions and work items are cited by id (`OQ-n`, `BL-nnn`).
