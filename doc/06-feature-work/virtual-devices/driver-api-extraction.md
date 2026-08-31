> Branch: `dev-split` — API extraction implemented 2026-08-17.
> Branch: `feat/simulated-bench` — drivers-as-plugins step implemented 2026-08-18.
> Branch: `feat/driver-plugins` — runtime loading + published driver, 2026-08-27.

# Hardware API extraction — drivers as runtime plugins

command-deck owns the hardware contract; the driver repos implement it and ship
self-registering plugin jars. The deck compiles with **zero** knowledge of any
driver class, and **no build of it packs a driver**: the boot jar runs through
`PropertiesLauncher` and loads plugins from the `loader.path` directories at
launch. A fresh clone builds with no vendor jar, and no image contains the
licence-restricted one — the practical half of **OQ-43**, whose remaining half
(procurement) is owner-owed.

Running is separate from building: `deck.hardware.mode=real` needs both provider
beans and fails startup without them. Nothing falls back to a simulator.

Served as step 1 of [`README.md`](README.md#order-of-work) and of
[`hardware-layer-redesign`](../hardware-layer-redesign/README.md).

## Contents

- [The two steps, and the objections the second had to answer](#the-two-steps-and-the-objections-the-second-had-to-answer)
- [Step 3 — off the classpath entirely](#step-3--off-the-classpath-entirely)
- [Where the code lives](#where-the-code-lives)
- [The API](#the-api)
- [Conformance guarantee](#conformance-guarantee)
- [Contract evolution](#contract-evolution)
- [Runtime discovery](#runtime-discovery)
- [Startup contract](#startup-contract)
- [Gradle wiring](#gradle-wiring)
- [Constraints](#constraints)
- [Open questions](#open-questions)

## The two steps, and the objections the second had to answer

Step 1 put the contract (`ch.rupfizupfi.deck.device.api`) in command-deck's main
tree and delegating adapters in an optional `drivers` source set, compiled only
when both jars sat in `lib/`. Drivers implementing the contract directly was
rejected then, on three grounds; step 2 shipped it anyway once each ground was
answered or knowingly accepted:

| Objection | Resolution |
|---|---|
| `usbmodbus` could not be rebuilt on the installed JDK (then OQ-76) — real operation would have stayed down | fell on its own: both repos now build from a clean checkout |
| `device.api` becomes an artifact both sibling repos compile against, with no cheap way to share it | not an artifact: `device-api/` is a standalone included build the driver repos `includeBuild` — they compile against the **live source**, no publish step, no version skew |
| a `Drive` change stops being two edits `javac` catches here and becomes a driver-jar rebuild the deck cannot start without | **accepted cost**: contract changes now require `shadowJar` + copy in both sibling repos. Bought with it: this repo no longer references any driver class, even from a build file |
| the `drivers` source set survives anyway — Spring needs the provider `@Component`s | it did not survive: each jar ships its own auto-configuration, so registration lives with the driver |

Git history has the step-1 layout (`command-deck/src/drivers/`).

## Step 3 — off the classpath entirely

Step 2 left the jars entering through the build (`runtimeOnly fileTree('lib')`),
which had two consequences it did not intend: the deck image built on the tester
**baked in** the non-redistributable `usbmodbus.jar`, and updating a driver meant
rebuilding the application. Step 3 moved loading to launch time.

| Decision | Why |
|---|---|
| `PropertiesLauncher` + `LOADER_PATH`, not the classpath | present = loaded, absent = not, and absence stays the same named startup failure. Swapping a driver is now a file copy plus a restart |
| `dscusb` published to GitHub Packages, staged by `stageDrivers` | ~~makes "the prod image automatically carries the public driver" mechanical~~ — **superseded 2026-08-31.** The image that was to carry it is the simulation deployment and wants no driver, so `stageDrivers`, the repository and the build token are gone. Publishing remains for consumers with no sibling checkout; the bench copies jars into `drivers/` |
| `usbmodbus.jar` supplied as a read-only host mount | the only way the restricted jar reaches a container without being redistributable in an image layer |
| Both jars untracked (`/drivers/*.jar` gitignored) | `dscusb.jar` was tracked binary churn once a published artifact existed, and a stale tracked copy silently diverging is worse than none |
| An unstageable driver **warns**, it does not fail the build | ~~lenient resolution plus a warning~~ — **moot 2026-08-31.** No build resolves a driver at all now, so there is nothing to be lenient about; the rule it protected ("never a build failure") holds trivially. Enforcement was always `HardwareModeCheck` |

Verified on the built artifact: `Main-Class` is
`org.springframework.boot.loader.launch.PropertiesLauncher`, `BOOT-INF/lib` holds
`device-api-1.0.0.jar` and no driver, and starting the jar with only `dscusb` on
`LOADER_PATH` in `real` mode fails naming **only** the missing `DriveProvider` —
proving the plugin's `AutoConfiguration.imports` is discovered from `loader.path`.

## Where the code lives

| Code | Home | Vendor imports |
|---|---|---|
| `ch.rupfizupfi.deck.device.api` — `Drive`, `DriveProvider`, `LoadCellStream`, `LoadCellStreamProvider`, `Measurement`, `StreamFailure` | `device-api/` — a **standalone included build** in this repo, coordinate `ch.rupfizupfi.deck:device-api`, zero dependencies | none |
| `Device` subclasses, `DeviceService`, `MotorSafetyController`, all test types | `command-deck` `src/main` | none — this is the point |
| `CellValueStreamAdapter`, `DeckLoadCellAutoConfiguration` | sibling repo `dscusb`, package `ch.rupfizupfi.dscusb.deck` | `CellValueStream`, `Measurement`, `CommandExecutionException` |
| `Cfw11Drive`, `DeckDriveAutoConfiguration` | sibling repo `usbmodbus`, package `ch.rupfizupfi.usbmodbus.deck` | `Cfw11` |
| `FourWayRelaySwitch` | `main`, unchanged | jSerialComm is a Maven dependency, not an optional jar |

The relay is deliberately out of scope: it never needed a jar, and
`FourWayRelaySwitch` is already subclassable for the fake.

`device-api` is standalone (own `settings.gradle`) rather than a third
subproject: the driver repos include that directory alone, so building a driver
never configures the deck's Spring Boot / Vaadin build — which is also why the
no-third-module decision in
[`shared-code-strategy.md`](../../02-modules/shared-code-strategy.md) stands.

## The API

Adapters are **pure delegation** — no conditionals, no policy. They are the only
code the simulated path never exercises, so anything clever in them is code that
first runs on the bench. The rule moved with them into the driver repos.

| Type | Shape |
|---|---|
| `Drive` | the 12 methods the deck calls (`setControlParameters`, `getControlParameters`, `setStart`, `setGeneralEnable`, `setDirection`, `getDirection`, `setUseSecondRamp`, `setSecondSpeedRampTime`, `setSpeedReferenceValueAsRpm`, `getMotorSpeedValueAsRpm`, `getMotorData`, `setActionInCaseOfCommunicationError`) plus `close()`, delegating to the vendor's own `Cfw11.close()` |
| `DriveProvider` | `Drive open()`. Required, not stylistic: `MotorSafetyController.java:267` opens a **fresh** handle mid-escalation, so tier 2 needs a factory rather than a singleton |
| `LoadCellStream` | **5** methods: `startReading`, `stopReading`, `getNextValues`, `isReading`, `lastError` |
| `LoadCellStreamProvider` | `LoadCellStream open()` — a stopped stream can never be restarted, see [`driver-jars.md`](../../03-backend/driver-jars.md#dscusbjar--load-cell) |
| `Measurement` | record `(float force, long timestamp)`, timestamp **epoch millis** — `ForceBroadcaster.java:20` compares it against wall clock |
| `StreamFailure` | record `(String driverCode, String failureType, String message)` |

`LoadCellStream` is five methods and not the reader loop's three because
`LoadCellDevice#getStreamFailure` also needs `isReading()` and the last error —
naming the driver's own trip cause is impossible without them.

`StreamFailure` carries **three** components, not the two originally planned:
`driverCode` is the `CommandExecutionException` error code and is **null** for
any other throwable, which is the only thing that lets `LoadCellDevice` keep its
two distinct operator messages apart. Folding the class name into `driverCode`
would have forced either string-parsing on the deck side or message formatting
inside an adapter — policy in the one place that must not hold any.

An interface for the drive is mandatory rather than stylistic: `Cfw11` is a
Kotlin class, so it is `final` and cannot be subclassed.
`Device.getHardwareComponent()` and both overrides were deleted outright rather
than widened — they had no callers, and the CFW11 one was the last way to reach
a drive handle outside the drive lock.

## Conformance guarantee

Both driver repos `includeBuild("../breaktest-command-deck/device-api")` and
compile their `deck` packages against the **live** contract: a `device.api`
change is a compile error on the next driver build. The contract and
`spring-boot-autoconfigure` are `compileOnly` there — the deck provides both at
runtime, and a copy bundled into a shadow jar would shadow the deck's own
classes.

The corollary of "this repo references no driver class": nothing *here* can
compile-check a jar. A contract change is verified by rebuilding the drivers,
which is why the sibling checkout stays preferred over the published fallback.

**What no compile can cover** is the jar actually on the machine — file-dropped
onto `loader.path`, so one built months ago can meet a newer contract unnoticed.
Each auto-configuration's initializer therefore calls
`DeviceApi.verifyPluginBuiltAgainst(ContractVersion.VALUE)`. `VALUE` is a
compile-time constant, so javac inlines it into the *driver's* class file: the
argument is the version the jar was built against, while `DeviceApi` reads the
one on the classpath. It must stay that literal — a runtime lookup would compare
the deployed contract to itself and always pass. `ContractVersion` is generated
from `project.version` in `device-api/build.gradle`, so the constant cannot drift
from the version the policy below is about.

`HardwareModeCheck` does not catch this: the bean *definitions* exist, so it
passes, and the initializer throws when Spring instantiates the configuration
class. Startup still fails, naming both versions and the jar to replace.

| Skew | Outcome |
|---|---|
| Different major | refused — a method a provider must implement changed |
| Plugin built against a newer minor | refused — it may call a `default` this contract lacks, otherwise a mid-run `NoSuchMethodError` |
| Plugin built against an older minor | allowed silently — defaults fill the gap, which is what a minor bump promises |

## Contract evolution

The policy travels with the contract — `device-api`'s `package-info.java` owns
it. In short: the driver repos are *providers*, so an added interface method is
breaking for them unless it is a `default` with a safe fallback (the post-Java-8
JDBC approach); record components cannot be added compatibly at all; the build's
version is semver **against providers** (default-method addition = minor,
anything a provider must implement = major), bumped on contract change, never
per app release. The runtime check above enforces that version, so a forgotten
bump does not merely mislead a reader — it disarms the only thing that catches a
stale jar.

`device-api` is published to GitHub Packages by
[`device-api.yml`](../../../.github/workflows/device-api.yml) on merge to `main`
whenever the version in `device-api/build.gradle` changes, and by hand with
`./gradlew -p device-api publish` (`GITHUB_ACTOR`/`GITHUB_TOKEN` set). Only the
API is ever published; the licence-restricted driver never needs to be.

Both driver repos include the sibling `device-api` directory **when it exists**
and fall back to that artifact when it does not — a checkout of the driver alone,
its own CI, a second machine. The sibling stays preferred deliberately: only it
tracks the live contract, so only it turns drift into an immediate driver-build
failure. The published fallback pins a version instead, and skew then surfaces at
the deck's startup.

## Runtime discovery

The driver packages sit outside the deck's `ch.rupfizupfi.deck` component-scan
root **deliberately**: registration goes through each jar's
`META-INF/spring/...AutoConfiguration.imports`. That is what makes a driver a
drop-in plugin — on the classpath the provider beans appear, off it nothing
does. Both auto-configurations carry
`@ConditionalOnProperty(deck.hardware.mode=real, matchIfMissing=true)`, the
exact mirror of the simulated providers' condition, so the two can never both
register and `simulated` can never resolve to hardware.

## Startup contract

`HardwareModeCheck` is a `BeanFactoryPostProcessor`, which is what makes it own
the error: it runs after the bean definitions are known but before any singleton
exists, so a missing provider is reported as a named jar rather than as
`DeviceService`'s `NoSuchBeanDefinitionException` on an interface. Mode table:
[`spring-boot-setup.md`](../../02-modules/spring-boot-setup.md#hardware-mode).

**Exemption:** the check stands down when `spring.aot.processing` is set.
`hillaGenerate` boots a Spring AOT context purely to discover
`@BrowserCallable` classes; without the exemption that context refuses to start
and the build itself depends on the vendor jars — exactly what this work removes.

## Gradle wiring

[`gradle-build.md`](../../02-modules/gradle-build.md#driver-plugins-loaderpath-not-the-classpath) owns
the detail. In short: root `settings.gradle` does `includeBuild 'device-api'`;
`:command-deck` depends on `ch.rupfizupfi.deck:device-api` (`implementation`) and
on no driver at all. The built `command-deck-application.jar` was verified to
carry `device-api-1.0.0.jar` and **no** driver jar in `BOOT-INF/lib`, no adapter
class in `BOOT-INF/classes`, and `PropertiesLauncher` as its `Main-Class`;
drivers arrive at launch over `loader.path`.
The Vaadin/Hilla tasks carry an explicit `dependsOn` onto the included build's
`:jar` because the plugin queries the runtime classpath mid-execution without
declaring it — a standalone `hillaGenerate` fails otherwise.

## Constraints

| Constraint | Why |
|---|---|
| `Measurement`'s JSON keys must stay `force` / `timestamp` | `ForceBroadcaster.java:21` sends it to `/topic/load-cell`, consumed by an **untyped** `rxStomp.watch()` at `StatusService.ts:77` and read as `item.force` / `item.timestamp` at `control.tsx:22` and `LiveTestResult.tsx:81`. `typecheck.ps1` cannot see this |
| `device-api` stays dependency-free | it lands on every consumer's classpath: the deck, both driver repos, and the boot jar |
| Adapters stay pure delegation, now in the driver repos | they are the only code the simulated path never runs |
| No build ever packs a driver | a missing jar must stay a startup failure named by `HardwareModeCheck`, never a build failure — and the licence-restricted `usbmodbus.jar` must not be redistributable by accident. `drivers/*.jar` enters as `developmentOnly`, which the Spring Boot plugin excludes from `bootJar`, so it reaches `bootRun` and never an artifact |
| One plugin directory, and `deck.hardware.mode` as the only switch | the build flag that used to gate `bootRun`'s driver classpath could only ever agree or disagree with the property that actually governs, since both driver auto-configurations are conditional on it exactly as the simulated pair is. `drivers/` is now read by `bootRun`, `driverPluginTest` and `run-bench.ps1` alike |
| The jars in `drivers/` are themselves tested (`driverPluginTest`) | the plugin's own skew check cannot catch a plugin that never calls it, and a jar built before that check existed loads clean and proves nothing. Verified against the real jars: each must make the call, satisfy the contract and register a provider, and with both present the real context must boot at `deck.hardware.mode=real` with the simulators displaced |
| Driver builds want the deck as a sibling checkout | their compile against `device-api` *is* the conformance check. They fall back to the published contract without one, but that pins a version instead of tracking it, so drift then waits until the deck's startup. Publishing `dscusb` did **not** change this either: a version published from a stale checkout compiles against a stale contract and nothing downstream catches it |
| `usbmodbus.jar` is never published | licence. It reaches the bench as a file in `drivers/` and reaches no image at all, since no image carries a driver |
| `LoadCellCheck` and a future `Cfw11Check` probe through the API | with no vendor code loaded in dev, a simulated provider must declare its own distinguishable identity — this forces **OQ-44** rather than deferring it |

## Open questions

| OQ | Effect |
|---|---|
| OQ-43 | build half **closed** — a fresh clone compiles, and the jar is now absent from git *and* from every image. Exposure is one host directory; provenance stays owner-owed |
| OQ-75, OQ-76 | **closed** since: both sibling repos build from a clean checkout and both jars are reproducible |
| OQ-44 | forced by the startup contract |
| OQ-50 | unchanged; `DriveProvider` preserves the fresh-handle path it turns on |
