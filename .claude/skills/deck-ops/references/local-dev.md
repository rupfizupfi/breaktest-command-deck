# Local development

## Which module to run

- CRUD/content work → `./gradlew :cms:bootRun`
- Hardware, dashboards, test execution → `./gradlew :command-deck:bootRun` (includes all cms views and services — usually the one to run)

The H2 path `./.data/deck` is working-directory-relative — depending on how it's launched, `.data/` may end up at the repo root or inside the module dir. If data "disappeared", check for multiple `.data/` dirs.

## Dev profile facts

- Default profile is `dev` (set via `spring.profiles.default`); no env var needed.
- Port 8080, H2 console at `/h2-console` (JDBC URL `jdbc:h2:file:./.data/deck`).
- Seed users from `cms/src/main/resources/data.sql` (runs only when DB is empty): `user` and `admin` — bcrypt hashes are in that file; roles `USER`/`ADMIN`.
- No hardware attached: device services throw when opening serial/USB connections — expected in dev; CRUD parts still work.
- `settings.json` at the repo root is a **runtime artifact** written by `SettingRepository` in dev — not config to edit or commit.
- Frontend hot reload works through Vite during `bootRun`. A new cms route not appearing in the deck app → restart the deck's `bootRun` (route-merge plugin runs at buildStart).

## Production build

```bash
./gradlew :command-deck:bootJar -Pvaadin.productionMode=true
./gradlew :cms:bootJar -Pvaadin.productionMode=true
```

The `-P` flag is required — root `build.gradle` defaults `productionMode = false`. The operator launcher `script/start.bat` starts `command-deck-application.jar` and opens `http://localhost` (add `:8080` manually in dev).

## Common startup failures

| Symptom | Cause |
|---|---|
| `Database may be already in use` | Other module (or a zombie JVM) holds the H2 lock — kill it |
| Port 8080 in use | Same |
| `cannot find symbol ... usbmodbus` | Missing `lib/usbmodbus.jar` |
| Compile error in a `tools.jackson`/`com.fasterxml` import | Jackson 3 package split — see the `deck-commit` skill |
| WebSocket data missing in views | `StatusService.ts` hardcodes `ws://localhost:8080/status` — only works in dev on localhost |
