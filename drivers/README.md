# drivers/ — the driver plugin directory

Where driver plugin jars go on this machine. The jars are not committed and are not build inputs.

`dscusb.jar` (load cell) and `usbmodbus.jar` (frequency converter) are runtime plugins: each
implements `ch.rupfizupfi.deck:device-api` in its own repo and registers its provider beans through
a Spring Boot auto-configuration. A missing jar is a startup refusal that `HardwareModeCheck`
names; add the jar and restart.

| Jar | Built from | Also available as |
|-----|-----------|-------------------|
| `dscusb.jar` | `../dscusb` → `./gradlew shadowJar` | published: `ch.rupfizupfi.dscusb:dscusb` |
| `usbmodbus.jar` | `../usbmodbus` → `./gradlew shadowJar` | nothing — licence-restricted, local only |

Everything that consumes a driver reads this directory:

| | How |
|---|---|
| `bootRun` | on the classpath as `developmentOnly`, which the Spring Boot plugin excludes from `bootJar` |
| `:command-deck:driverPluginTest` | same jars, `deck.hardware.mode=real`, asserts the providers register |
| `script/run-bench.ps1` | `LOADER_PATH`, the launcher's own plugin mechanism |

Both auto-configurations are conditional on `deck.hardware.mode=real` (the default when unset), so
only dev's explicit `simulated` leaves the jars inert. To drive the bench from a dev loop:

```powershell
./gradlew :command-deck:bootRun --args='--deck.hardware.mode=real'
```

Each jar's `Implementation-Version` manifest attribute holds the `version` from its repo's
`gradle.properties`:

```powershell
unzip -p drivers/dscusb.jar META-INF/MANIFEST.MF
```

`driverPluginTest` refuses a jar without one, and refuses two jars whose shared bundled classes
differ: each plugin bundles its own Kotlin stdlib and the launcher loads whichever jar it lists
first, so the Kotlin pin has to match across the two driver repos.

Both drivers are Windows-only (a Win32 vendor DLL, the Thesycon USBIO kernel driver) and refuse to
register elsewhere. No container carries a driver: the deck container runs simulated, and the
native bench started by `script/run-bench.ps1` is the only deployment that reads jars from here.
See `doc/dev/backend/driver-jars.md`.
