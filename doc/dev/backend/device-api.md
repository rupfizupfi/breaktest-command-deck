# Device API — drivers as runtime plugins

command-deck owns the hardware contract; the driver repos implement it and ship
self-registering plugin jars. The deck compiles with no knowledge of any driver
class, and no build of it packs a driver: the boot jar runs through
`PropertiesLauncher` and loads plugins from the `loader.path` directories at
launch. A fresh clone builds with no vendor jar, and no image contains the
licence-restricted one.

Running is separate from building: `deck.hardware.mode=real` needs both provider
beans and fails startup without them. Nothing falls back to a simulator.

## Contents

- [Steps 1 and 2](#steps-1-and-2)
- [Step 3 — off the classpath entirely](#step-3--off-the-classpath-entirely)
- [Where the code lives](#where-the-code-lives)
- [The API](#the-api)
- [The call gate (1.5.0)](#the-call-gate-150)
- [Conformance guarantee](#conformance-guarantee)
- [Contract evolution](#contract-evolution)
- [Runtime discovery](#runtime-discovery)
- [Startup contract](#startup-contract)
- [Gradle wiring](#gradle-wiring)
- [Constraints](#constraints)

## Steps 1 and 2

Step 1 kept adapters in a `drivers` source set (`command-deck/src/drivers/`, in git history). Step 2
has the drivers implement the contract against `device-api/`'s live source and register their own
providers; its accepted cost and the objections it answered are in [decisions](../../decisions.md#accepted-with-a-known-cost).

## Step 3 — off the classpath entirely

With the jars entering through the build (`runtimeOnly fileTree('lib')`), the deck
image baked in the non-redistributable `usbmodbus.jar` and updating a driver meant
rebuilding the application. Step 3 moves loading to launch time.

| Decision | Why |
|---|---|
| `PropertiesLauncher` + `LOADER_PATH`, not the classpath | present = loaded, absent = not, and absence stays the same named startup failure. Swapping a driver is a file copy plus a restart |
| `dscusb` published to GitHub Packages | for consumers with no sibling checkout. The image-staging step (`stageDrivers`) was dropped 2026-08-31: the deck image is the simulation deployment and carries no driver; the bench copies jars into `drivers/` |
| No driver reaches any image | superseded the read-only host mount for `usbmodbus.jar` (2026-08-31): the only deployment that drives hardware is the native bench |
| Both jars untracked (`/drivers/*.jar` gitignored) | a tracked `dscusb.jar` was binary churn next to a published artifact, and a stale tracked copy is worse than none |
| A missing driver is never a build failure | no build resolves a driver; `HardwareModeCheck` enforces presence at startup |

Verified on the built artifact (layout under [Gradle wiring](#gradle-wiring)): with only `dscusb` on
`LOADER_PATH` in `real` mode it fails naming only the missing `DriveProvider`, so the plugin's
`AutoConfiguration.imports` is discovered from `loader.path`.

## Where the code lives

| Code | Home | Vendor imports |
|---|---|---|
| `ch.rupfizupfi.deck.device.api` — `Drive`, `DriveProvider`, `LoadCellStream`, `LoadCellStreamProvider`, `Measurement`, `StreamFailure`, `DeviceApi`; `.support` — the [call gate](#the-call-gate-150) | `device-api/` — a standalone included build in this repo, coordinate `ch.rupfizupfi.deck:device-api`, zero dependencies | none |
| `Device` subclasses, `DeviceService`, `MotorSafetyController`, all test types | `command-deck` `src/main` | none |
| `DeckLoadCellAutoConfiguration` | sibling repo `dscusb`, package `ch.rupfizupfi.dscusb.deck` | `CellValueStream`, which since `0.3.0` implements `LoadCellStream` itself |
| `Cfw11Drive`, `DeckDriveAutoConfiguration` | sibling repo `usbmodbus`, package `ch.rupfizupfi.usbmodbus.deck` | `Cfw11` |
| `FourWayRelaySwitch` | `main` | jSerialComm is a Maven dependency, not an optional jar |

The relay never needed a jar; its provider shape is described in [`simulated-bench.md`](simulated-bench.md).

`device-api` is standalone (own `settings.gradle`) rather than a third
subproject: the driver repos include that directory alone, so building a driver
never configures the deck's Spring Boot / Vaadin build — which is also why the
no-third-module decision in
[`shared-code-strategy.md`](../modules/shared-code-strategy.md) stands.

## The API

Adapters are **pure delegation** — no conditionals, no policy. They are the only
code the simulated path never exercises, so any logic in them first runs on the
bench. The rule applies in the driver repos, where the adapters live.

| Type | Shape |
|---|---|
| `Drive` | the 12 methods the deck calls (`setControlParameters`, `getControlParameters`, `setStart`, `setGeneralEnable`, `setDirection`, `getDirection`, `setUseSecondRamp`, `setSecondSpeedRampTime`, `setSpeedReferenceValueAsRpm`, `getMotorSpeedValueAsRpm`, `getMotorData`, `setActionInCaseOfCommunicationError`) plus `close()`, delegating to the vendor's own `Cfw11.close()`, the `default` `enableLinkLossStop(Duration)` (1.2.0), whose fallback throws, the `default` `readControlSource()` (1.3.0), whose fallback answers `UNKNOWN`, and the 1.5.0 gate defaults ([below](#the-call-gate-150)) |
| `DriveProvider` | `Drive open()`. A factory, because tier 2 opens a fresh handle mid-escalation (`MotorSafetyController`) |
| `LoadCellStream` | `startReading`, `stopReading`, `getNextValues`, `isReading`, `lastError`, plus the `default` `droppedSampleCount()` (1.1.0) |
| `LoadCellStreamProvider` | `LoadCellStream open()` — a stopped stream can never be restarted, see [`driver-jars.md`](driver-jars.md#dscusbjar--load-cell) |
| `Measurement` | record `(float force, long timestamp)`, timestamp **epoch millis** — `ForceBroadcaster.java:20` compares it against wall clock |
| `StreamFailure` | record `(String driverCode, String failureType, String message)` |

`LoadCellStream` has more than the reader loop's three methods because
`LoadCellDevice#getStreamFailure` needs `isReading()` and the last error to name the
driver's own trip cause.

`StreamFailure.driverCode` is the `CommandExecutionException` error code and is
**null** for any other throwable; that is what keeps `LoadCellDevice`'s two operator
messages apart. Folding the class name into it would force string parsing on the deck
side or formatting inside an adapter.

The drive needs an interface because `Cfw11` is a Kotlin class, so it is `final` and
cannot be subclassed. `Device.getHardwareComponent()` is gone: it had no callers, and
the CFW11 override was a way to reach the drive handle outside the drive lock.

## The call gate (1.5.0)

Phase 1 of the drive-call-gating plan (umbrella `doc/@work/plans/drive-call-gating.md`): the
contract a driver gates its own calls with, on which the deck dropped its `driveLock` (BL-121). Additive, so
a minor bump: a 1.4.0 jar still loads, answers "caller serializes", and throws from `stopEpoch`,
`runLinked` and `async` (the 1.2.0 pattern). 1.6.0 bounds a gated `close()` (row below) and adds `DriveClosePendingException` and `TransactionScheduler`'s last-act constructor.

| Addition | Shape |
|---|---|
| `stop()` | idempotent, STOP lane; the default is the deck's two-call stop (one-write stop, then zero speed), the second sent whatever the first threw - an `Error` or an undeclared checked vendor exception included - and the first rethrown as itself |
| `gating()` → `DriveGating` | `CALLER_SERIALIZES` by default; a gated provider answers `DRIVER_SERIALIZES`. Such a plugin is refused at every energize (BL-121), so it has a `ContractFeature` row (`DRIVER_GATING`) and the deck warns at startup |
| `stopEpoch()`, `runLinked(epoch, Consumer<Drive>)` | every STOP-lane submission bumps the epoch; a group fenced on it runs its calls back to back with only STOP between them, and `StaleStopEpochException` reports a stop that intervened |
| `async()` → `DriveAsync` | a `CompletableFuture` twin of every operation, in the same lane |
| `DriveDeadlineException` | a `DriveCommunicationException`: a lane deadline from submission passed before dispatch or reply. The deck's "wedged" tier outcome since it dropped its lock (BL-121) |
| `DriveLane` | STOP, CONTROL, SETPOINT, STATUS, CONFIG, in dispatch order; the operation implies the lane (table in its javadoc), priority is never a parameter. STOP's deadline is 1500 ms, the tier-1 lock deadline it replaced |

| Decision point (BL-114) | Decision | Why |
|---|---|---|
| Where the scheduler lives | here, in `device-api`'s `support` package: `TransactionScheduler` (one daemon I/O thread per handle, lock-free STOP queue, deadlines, epoch, groups, cancel-on-stop) and `GatedDrive` over a provider's `DriveOperations`. JDK only, logs nothing | one implementation and one suite for a safety-relevant algorithm, and the simulated bench runs the gate a gated vendor driver will run (BL-116/117; no gated CFW11 driver exists yet). Per provider, the driver's scheduler would be code only the bench executes, against the pure-delegation rule above. Cost: device-api carries threads; drivers keep it `compileOnly`, so a scheduler fix ships with the deck. For the same reason `support`'s public and protected signatures (`DriveOperations`, `GatedDrive`'s constructor and overridable methods, `LaneDeadlines`, `TransactionScheduler`'s public API) are binary contract for a provider built on them - the skew check compares only the version - so a change there follows the minor/major rules; only an implementation change bumps nothing |
| Futures on `Drive` or a separate view | a separate `DriveAsync` | twins on `Drive` double the interface, and a provider overriding neither direction would recurse |
| Who runs completions | the scheduler, on a virtual thread per async completion, never the I/O thread; a sync call waits on the transaction itself and is refused on the I/O thread | a dependent stage must not stall the bus, and a slow completion thread must not turn a stop that went out into a deadline failure |
| Linked group API | generic `runLinked(epoch, Consumer<Drive>)`, not a semantic `energize(...)` | the energize sequence is deck policy (`MotorSafetyController#energize` already takes a `Consumer<Drive>`) |
| Stops and groups | while the handle is open a stop is never refused, not even from a group's own thread, and one that goes out while a group is open cuts it whatever epoch the group read. For the whole body, cut or abandoned or not, the handle and its async view refuse the body's thread everything but a stop | the epoch alone misses a stop bumped before the group read it but queued after; and a body's call on the handle after the cut would carry the new epoch and go out after the stop |
| A queued STOP | never dropped once queued: past its deadline it fails its caller with `DriveDeadlineException` and is still sent once the transaction ahead ends, and every STOP queued before `close()` goes out before the port closes (other lanes drop an expired request; `close()` fails them). One submitted after `close()` began is refused with `IllegalStateException` | a stop is idempotent and stopping is the safe direction, and tier 2 closes the handle exactly when tier 1's stop failed; a closed handle has no session left for a later stop, which goes through a fresh handle |
| What is left to the driver (BL-116/117) | control-word cache, setpoint coalescing, anti-starvation, keep-alive injection; the simulator's CONTROL/SETPOINT/CONFIG/STATUS deadlines (1000/1000/2000/500 ms) stand in until then | they are transport policy; the deck dropped its `driveLock` and its keep-alive in phase 3 (BL-121/122), and the simulator feeds P0314 itself (`SimulatedLinkKeepAlive`) |
| Who feeds P0314 (1.7.0) | a `DRIVER_SERIALIZES` provider, from a successful `enableLinkLossStop` until a de-energizing call or `close()`, injecting a STATUS read when its own traffic pauses, so a caller re-arms before each energize (`MotorSafetyController#energize` does); a `CALLER_SERIALIZES` caller still owes the traffic itself. Held by `DriveContractTest#aGatedProviderFeedsTheLinkWatchdogItArmedWhileEnergized` (900 ms timeout, caller silent for 2.25 s, at most one timeout between requests the plant answered) through the fixture's `lastRequestNanos` | the deck sends a gated drive no keep-alive (BL-122), so an obligation the javadoc only implied would let a gated jar that never feeds pass every check, and the drive coast once caller and dashboard poll (two STATUS reads every 400 ms while info broadcasting runs) fall silent for the timeout. It fails safe and has no `ContractFeature` row: a gated usbmodbus jar built before its keep-alive (3c99479) loads without a warning, and rebuilding it is the fix |
| What `close()` waits for (1.6.0) | it rejects new calls at once; the I/O thread finishes the transaction in flight and sends the queued STOPs. `close()` waits for that at most the STOP deadline (1500 ms) and returns only with the port closed; past the bound it throws `DriveClosePendingException` - not a comms failure - with the port still held. The guarantee is that the port is closed no later than the first later `close()` that returns normally (each waits the same bound), so calling it again is how a caller waits for the port. Where the port close runs is the provider's: one that passes it to `TransactionScheduler` as the last act (`GatedDrive`, whose `DriveOperations.close()` runs on the I/O thread and whose failure reaches the first `close()` that sees the thread end; the simulator) closes the port by itself once the transfer ends; one on the 2-argument constructor that closes the port on the closing thread after the scheduler's close (usbmodbus `Cfw11Drive`) leaves it open until that later `close()` - for the deck, the next `connect()`'s retry. A still-wedged I/O thread is a daemon: at JVM exit nobody closes that port and the OS reclaims the USB handle. 1.5.0 waited without a bound | a close that returned with a transfer in flight would let tier 2 open a second session on the bus (OQ-50) and let a wedged energizing write land after its stop; one without a bound let a never-ending wedge stall tier 2 so tier 3 never reported (BL-148). The deck's side is tier 2 in [`safety-and-recovery.md`](safety-and-recovery.md) |

**The bus stop is latency, not the safety case: P0313/P0314 and the hardwired channel are the
guarantee.** A gated stop waits for the transaction in flight, and nothing aborts a wedged one;
`SimulatedLinkLossStopTest` shows the drive's watchdog coasting the motor before the stop's deadline.

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

No compile covers the jar actually on the machine: it is file-dropped onto
`loader.path`, so one built months ago can meet a newer contract unnoticed. Each
auto-configuration's initializer therefore calls
`DeviceApi.verifyPluginBuiltAgainst(ContractVersion.VALUE)`. `VALUE` is a
compile-time constant, so javac inlines it into the *driver's* class file: the
argument is the version the jar was built against, while `DeviceApi` reads the one
on the classpath. It must stay a constant — a runtime lookup would compare the
deployed contract to itself and always pass. `ContractVersion` is generated from
`project.version` in `device-api/build.gradle`.

`HardwareModeCheck` does not catch this: the bean *definitions* exist, so it
passes, and the initializer throws when Spring instantiates the configuration
class. Startup still fails, naming both versions and the jar to replace.

A check that lives in the plugin cannot catch a jar that never calls it, so the
deck checks the other side too. A passing call is recorded under the calling
class (`DeviceApi.reportedBuiltAgainst()`), and in real mode `PluginContractCheck`,
a `BeanPostProcessor`, refuses startup — in `HardwareModeCheck`'s format, naming
the bean, its class and its jar — for any `DriveProvider` or
`LoadCellStreamProvider` that no recorded class vouches for. The provider class,
any class enclosing it (Kotlin's anonymous `object :` providers) or the class
declaring its `@Bean` method vouches, so the call belongs in the
auto-configuration that declares the provider or in the provider itself; routed
through any other class it is refused. Simulated mode and the build's AOT context
are exempt, as the simulators are deck code.

| Skew | Outcome |
|---|---|
| Different major | refused — a method a provider must implement changed |
| Plugin built against a newer minor | refused — it may call a `default` this contract lacks, otherwise a mid-run `NoSuchMethodError` |
| Plugin built against an older minor | allowed — defaults fill the gap, which is what a minor bump promises; the deck logs a startup warning per `ContractFeature` the jar predates, e.g. a load-cell jar before 1.1.0 does not report dropped samples, a drive jar before 1.5.0 does not gate its own calls |
| Plugin never calls the check | refused by `PluginContractCheck` |

## Contract evolution

`device-api`'s `package-info.java` owns the policy. In short: the version is semver **against
providers** (a `default` with a safe fallback = minor, anything a provider must implement = major;
`support`'s public signatures count too), bumped on contract change, never per app release, and the
runtime check above enforces it. A `default` whose fallback hides something from the operator owes a
row in the deck's `ContractFeature`; a fallback that throws fails closed and needs none.

Behaviour is held by tests, not the compiler (1.4.0). `device-api`'s `java-test-fixtures` carry
abstract `DriveContractTest` and `LoadCellStreamContractTest`; each provider supplies a fixture and
runs them in its own suite — `SimulatedDriveContractTest` and `SimulatedLoadCellStreamContractTest`
here, `Cfw11DriveContractTest` and `CellValueStreamContractTest` in the driver repos. A behaviour
change is a change to those tests in the same bump (fixture SPI rules: `package-info`), as is an obligation a capability implies (1.7.0's P0314 feed, row above).
`device-api`'s own suite runs inside `:command-deck:test`, never `UP-TO-DATE`.

[`device-api.yml`](../../../.github/workflows/device-api.yml) publishes `device-api` to GitHub
Packages on merge to `main` when the version in `device-api/build.gradle` changes; by hand,
`./gradlew -p device-api publish` (`GITHUB_ACTOR`/`GITHUB_TOKEN` set). The artifact is the contract
plus its `support` package (the call gate); the licence-restricted driver never needs to be published.

Both driver repos include the sibling `device-api` directory when it exists and
fall back to that artifact when it does not (a driver-only checkout, its own CI, a
second machine). The sibling is preferred because only it tracks the live contract
and turns drift into an immediate driver-build failure; the published fallback pins
a version, so skew surfaces at the deck's startup.

## Runtime discovery

The driver packages sit outside the deck's `ch.rupfizupfi.deck` component-scan
root: registration goes through each jar's
`META-INF/spring/...AutoConfiguration.imports`, so a driver is a drop-in plugin —
on the classpath the provider beans appear, off it nothing does. Both auto-configurations carry
`@ConditionalOnProperty(deck.hardware.mode=real, matchIfMissing=true)`, the
exact mirror of the simulated providers' condition, so the two can never both
register and `simulated` can never resolve to hardware.

## Startup contract

`HardwareModeCheck` is a `BeanFactoryPostProcessor`: it runs after the bean
definitions are known but before any singleton exists, so a missing provider is
reported as a named jar rather than as `DeviceService`'s
`NoSuchBeanDefinitionException` on an interface. Mode table:
[`spring-boot-setup.md`](../modules/spring-boot-setup.md#hardware-mode).

**Exemption:** the check stands down when `spring.aot.processing` is set.
`hillaGenerate` boots a Spring AOT context purely to discover
`@BrowserCallable` classes; without the exemption that context refuses to start
and the build itself would depend on the vendor jars.

## Gradle wiring

[`gradle-build.md`](../modules/gradle-build.md#driver-plugins-loaderpath-not-the-classpath) owns
the detail. In short: root `settings.gradle` does `includeBuild 'device-api'`;
`:command-deck` depends on `ch.rupfizupfi.deck:device-api` (`implementation`) and
on no driver at all. The built `command-deck-application.jar` was verified to
carry `device-api-<version>.jar` and no driver jar in `BOOT-INF/lib`, no adapter
class in `BOOT-INF/classes`, and `PropertiesLauncher` as its `Main-Class`;
drivers arrive at launch over `loader.path`.
The Vaadin/Hilla tasks carry an explicit `dependsOn` onto the included build's
`:jar` because the plugin queries the runtime classpath mid-execution without
declaring it — a standalone `hillaGenerate` fails otherwise.

## Constraints

| Constraint | Why |
|---|---|
| `Measurement`'s JSON keys must stay `force` / `timestamp` | `ForceBroadcaster.java:21` sends it to `/topic/load-cell`, consumed by an **untyped** `rxStomp.watch()` in `StatusService.ts` and read as `force` / `timestamp` only in `command-deck/src/main/frontend/service/loadCellBatch.ts`. `typecheck.ps1` cannot see this |
| `device-api` stays dependency-free, its `support` package JDK only | it lands on every consumer's classpath: the deck, both driver repos, and the boot jar |
| Adapters stay pure delegation, now in the driver repos | they are the only code the simulated path never runs |
| No fallback in either direction | `real` without a provider is a startup failure; absent hardware must never select the simulator |
| No build ever packs a driver | a missing jar must stay a startup failure named by `HardwareModeCheck`, never a build failure — and the licence-restricted `usbmodbus.jar` must not be redistributable by accident. `drivers/*.jar` enters as `developmentOnly`, which the Spring Boot plugin excludes from `bootJar`, so it reaches `bootRun` and never an artifact |
| One plugin directory, and `deck.hardware.mode` as the only switch | both driver auto-configurations are conditional on the property, exactly as the simulated pair is, so a second build flag could only agree or disagree with it. `drivers/` is read by `bootRun`, `driverPluginTest` and `run-bench.ps1` alike |
| The jars in `drivers/` are themselves tested (`driverPluginTest`) | a plugin that never calls the skew check is refused only at startup, by `PluginContractCheck`; the gate names the jar before it is deployed. Each jar must make the call, satisfy the contract and register a provider; with both present the real context must boot at `deck.hardware.mode=real` with the simulators displaced |
| Driver builds want the deck as a sibling checkout | their compile against `device-api` is the conformance check. Without it they fall back to the published contract, which pins a version, so drift waits until the deck's startup. The same holds for published `dscusb`: a version published from a stale checkout compiles against a stale contract |
| `usbmodbus.jar` is never published | licence. It reaches the bench as a file in `drivers/` and no image carries it |
| `LoadCellCheck` and `FrequencyInverterCheck` probe through the API | with no vendor code loaded in dev, a simulated provider must declare its own distinguishable identity (OQ-44) |
