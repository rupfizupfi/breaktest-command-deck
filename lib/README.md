# lib/ — bench-local driver plugins

Nothing here is committed, and nothing here is a build input. This directory exists for one job:
driving **real hardware from a dev machine** through `bootRun`.

```powershell
./gradlew :command-deck:bootRun -PdeckDrivers=local
```

That option puts every `lib/*.jar` on `bootRun`'s classpath as `developmentOnly`, which the Spring
Boot plugin excludes from `bootJar` — so a jar built while this directory is full is still
driver-free. Without the option, `bootRun` sees no driver at all, which is the normal dev state
(`application-dev.properties` runs `deck.hardware.mode=simulated`).

Put the two plugin jars here when you need them:

| Jar | Built from | Also available as |
|-----|-----------|-------------------|
| `dscusb.jar` | `../dscusb` → `./gradlew shadowJar` | published: `ch.rupfizupfi.dscusb:dscusb` |
| `usbmodbus.jar` | `../usbmodbus` → `./gradlew shadowJar` | nothing — licence-restricted, local only |

**In production the drivers do not come from here.** The boot jar runs through
`PropertiesLauncher` and loads plugins from the directories named by `LOADER_PATH`; the container
gets the public driver at `/app/drivers` (staged from GitHub Packages by
`:command-deck:stageDrivers`) and the restricted one from the `docker/drivers-local/` host mount at
`/app/drivers-local`. See `doc/03-backend/driver-jars.md`.
