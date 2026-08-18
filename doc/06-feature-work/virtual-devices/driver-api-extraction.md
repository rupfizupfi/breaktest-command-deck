> Branch: `dev-split` — implemented 2026-08-17.

# Hardware API extraction — both driver jars optional

command-deck owns the hardware contract; the vendor adapters sit in an optional
Gradle source set. The **main** source set compiles without `lib/dscusb.jar` and
`lib/usbmodbus.jar`, so a fresh clone builds with no vendor jar at all — the
practical half of **OQ-43**, whose remaining half (procurement) is owner-owed.

Running is separate from building: `deck.hardware.mode=real` needs both provider
beans and fails startup without them. Nothing falls back to a simulator.

Served as step 1 of [`README.md`](README.md#order-of-work) and of
[`hardware-layer-redesign`](../hardware-layer-redesign/README.md).

## Contents

- [Where the code lives](#where-the-code-lives)
- [The API](#the-api)
- [Startup contract](#startup-contract)
- [Gradle wiring](#gradle-wiring)
- [Constraints](#constraints)
- [Open questions](#open-questions)

**Rejected alternative:** having `dscusb` / `usbmodbus` implement the deck's
interfaces directly is a cleaner object model, but both jars must then be rebuilt
to satisfy the new contract, and `usbmodbus` cannot be built on the installed JDK
(**OQ-76**) — real-hardware operation would have stayed down until that migration
landed. The shipped variant leaves both sibling repos untouched.

## Where the code lives

| Code | Source set | Vendor imports |
|---|---|---|
| `ch.rupfizupfi.deck.device.api` — `Drive`, `DriveProvider`, `LoadCellStream`, `LoadCellStreamProvider`, `Measurement`, `StreamFailure` | `main` | none |
| `Device` subclasses, `DeviceService`, `MotorSafetyController`, all test types | `main` | none — this is the point |
| `ch.rupfizupfi.deck.device.vendor` — `Cfw11Drive`, `CellValueStreamAdapter`, their `@Component` providers | `drivers` (`command-deck/src/drivers/java`) | `Cfw11`, `CellValueStream`, `Measurement`, `CommandExecutionException` |
| `FourWayRelaySwitch` | `main`, unchanged | jSerialComm is a Maven dependency, not an optional jar |

The relay is deliberately out of scope: it never needed a jar, and
`FourWayRelaySwitch` is already subclassable for the fake.

`.gitignore`'s browser-driver rule had to be anchored to `/drivers/` — unanchored
it matched `command-deck/src/drivers/` too, and the adapters never appeared in
`git status` at all.

## The API

Adapters are **pure delegation** — no conditionals, no policy. They are the only
code the simulated path never exercises, so anything clever in them is code that
first runs on the bench.

| Type | Shape |
|---|---|
| `Drive` | the 12 methods the deck calls (`setControlParameters`, `getControlParameters`, `setStart`, `setGeneralEnable`, `setDirection`, `getDirection`, `setUseSecondRamp`, `setSecondSpeedRampTime`, `setSpeedReferenceValueAsRpm`, `getMotorSpeedValueAsRpm`, `getMotorData`, `setActionInCaseOfCommunicationError`) plus `close()`, which is where `getUsbComm().closeUSBComm()` goes |
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
Kotlin class, so it is `final` and cannot be subclassed. `close()` is on `Drive`
because `getUsbComm().closeUSBComm()` is the one vendor member with no home in a
domain interface. `Device.getHardwareComponent()` and both overrides were deleted
outright rather than widened — they had no callers, and the CFW11 one was the
last way to reach a drive handle outside the drive lock.

Owning `Measurement` also decouples the deck from the in-flight package move in
the `dscusb` repo (**OQ-75**): a rebuild that relocates `CellValueStream` now
breaks only the two adapters, not the main tree.

## Startup contract

`HardwareModeCheck` is a `BeanFactoryPostProcessor`, which is what makes it own
the error: it runs after the bean definitions are known but before any singleton
exists, so a missing provider is reported as a named jar rather than as
`DeviceService`'s `NoSuchBeanDefinitionException` on an interface.

| `deck.hardware.mode` | Outcome |
|---|---|
| `real` (default everywhere) | both providers present → start; either missing → fail, naming the jars and `doc/03-backend/driver-jars.md` |
| `simulated` | always fails — the simulated providers do not exist yet (**OQ-62**) |
| anything else | fails, listing the valid values |

**Exemption:** the check stands down when `spring.aot.processing` is set.
`hillaGenerate` boots a Spring AOT context purely to discover
`@BrowserCallable` classes; without the exemption that context refuses to start
and the build itself depends on the vendor jars — exactly what this work removes.

## Gradle wiring

`command-deck/build.gradle` registers the `drivers` source set only when **both**
jars are present (real mode needs both, so per-device gating buys nothing), with
compile classpath = main output + main compile classpath + the two jars. Both
`bootRun` and `bootJar` get the drivers output **and** the jars; the built
`command-deck-application.jar` was verified to carry the four adapter classes in
`BOOT-INF/classes` and both jars in `BOOT-INF/lib`.

Neither `command-deck/build.gradle` nor the root `subprojects` block carries any
`fileTree` / `flatDir` vendor wiring. No new subproject: the root block applies the
Spring Boot and Vaadin plugins to every module, neither of which a plain adapter
library should carry. Details in
[`gradle-build.md`](../../02-modules/gradle-build.md#the-drivers-source-set).

## Constraints

| Constraint | Why |
|---|---|
| `Measurement`'s JSON keys must stay `force` / `timestamp` | `ForceBroadcaster.java:21` sends it to `/topic/load-cell`, consumed by an **untyped** `rxStomp.watch()` at `StatusService.ts:77` and read as `item.force` / `item.timestamp` at `control.tsx:22` and `LiveTestResult.tsx:81`. `typecheck.ps1` cannot see this |
| Adapters stay pure delegation | they are the only code the simulated path never runs |
| No fallback in either direction | `real` without a provider is a startup failure; absent hardware must never select the simulator ([`machine-safety.md`](../../_research/machine-safety.md)) |
| `LoadCellCheck` and a future `Cfw11Check` probe through the API | with no vendor code loaded in dev, a simulated provider must declare its own distinguishable identity — this forces **OQ-44** rather than deferring it |
| Bench still needs both jars in `lib/` | only the dev *build* classpath changed; real operation is unchanged |

## Open questions

| OQ | Effect |
|---|---|
| OQ-43 | build half **closed** — a fresh clone compiles. Provenance stays owner-owed |
| OQ-76 | demoted from blocker to "needed before the next `usbmodbus` rebuild" |
| OQ-75 | hazard contained, see above |
| OQ-44 | forced by the startup contract |
| OQ-50 | unchanged; `DriveProvider` preserves the fresh-handle path it turns on |
