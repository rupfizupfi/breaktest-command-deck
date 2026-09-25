# drivers/ — the driver plugin directory

The one place a driver plugin jar goes on this machine. Nothing here is committed, and nothing
here is a build input.

`dscusb.jar` (load cell) and `usbmodbus.jar` (frequency converter) are runtime plugins: each
implements `ch.rupfizupfi.deck:device-api` in its own repo and registers its provider beans through
a Spring Boot auto-configuration. A jar present here is loaded, a jar absent is not, and absence is
a *startup* refusal that `HardwareModeCheck` names — put the jar in place and restart, there is
nothing to rebuild.

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

Which of the two providers you get is decided at runtime by `deck.hardware.mode`, not by the build:
both auto-configurations are conditional on `real` and treat an absent `deck.hardware.mode` as
`real`, so only dev's explicit `simulated` leaves the jars here inert. To drive the bench from a dev
loop:

```powershell
./gradlew :command-deck:bootRun --args='--deck.hardware.mode=real'
```

Each jar carries an `Implementation-Version` manifest attribute holding the `version` from its own
repo's `gradle.properties` — the only thing in the file that names the build it came from:

```powershell
unzip -p drivers/dscusb.jar META-INF/MANIFEST.MF
```

`driverPluginTest` refuses a jar without one, and refuses two jars whose shared bundled classes
differ: each plugin bundles its own Kotlin stdlib and the launcher loads whichever jar it lists
first, so the Kotlin pin has to match across the two driver repos.

**Both drivers are Windows-only** (a Win32 vendor DLL, the Thesycon USBIO kernel driver) and refuse
to register elsewhere, which is why the Linux container cannot drive the machine.

No container carries a driver. The deck's docker profile is the simulation and test
deployment (`deck.hardware.mode=simulated`), so the bench — native Windows, started by
`script/run-bench.ps1` — is the only deployment that reads a jar from here. See
`doc/03-backend/driver-jars.md`.
