> Branch: `dev-split` — design captured 2026-08-17. **Step 1 shipped; steps 2–5 are not
> implemented** and adoption is not decided (OQ-64).

# Design: hardware + test-runner layer redesign

A structural refactor of `command-deck`'s drive, device and test-runner packages with **no
intended behaviour change**: same three-tier stop semantics, same load-cell detectors, same
verification against measured motor speed. What moves is where correctness lives — out of
javadoc and caller discipline, into types and single ownership.

Three properties are the whole point: **one lock per resource**, **one home per rule**, **one
code path per operation**. Every item below is traceable to one of them.

Type sketches and the invariants each one buys: [`target-design.md`](target-design.md).

## Contents

- [Relation to the sibling designs](#relation-to-the-sibling-designs)
- [What it fixes](#what-it-fixes)
- [The dependency rule](#the-dependency-rule)
- [Migration order](#migration-order)
- [What stays unchanged, deliberately](#what-stays-unchanged-deliberately)
- [Where to look in the code](#where-to-look-in-the-code)
- [Open questions](#open-questions)

## Relation to the sibling designs

Read this section before starting work — two of the three overlap on purpose.

| Doc | Relation |
|---|---|
| [`../virtual-devices/README.md`](../virtual-devices/README.md) | **Same first step.** The `Drive` / `LoadCellStream` seams are step 1 here; the extraction plan is [`../virtual-devices/driver-api-extraction.md`](../virtual-devices/driver-api-extraction.md). Build the seam once and both designs proceed; that doc owns the simulator, the mode contract and the fault-injection switches. |
| [`../testrunner-safety/README.md`](../testrunner-safety/README.md) | **Behavioural, where this is structural.** Its phases 2–4 change what the runner *does*; this changes where the code lives. Doing this first makes those phases smaller; doing them first is also fine. Neither may alter tier semantics. |
| [`../../03-backend/hardware-integration.md`](../../03-backend/hardware-integration.md) | Owns device internals and the STOMP broadcast path. |
| [`../../03-backend/test-execution-engine.md`](../../03-backend/test-execution-engine.md) | Owns the current lifecycle: service → factory → thread → signal bus. |
| [`../../03-backend/test-types.md`](../../03-backend/test-types.md) | Owns `AbstractTest` and the three strategies as they are today. |

## What it fixes

Each row is a cause, not a symptom. Anchors are current code.

| Cause | Evidence | Redesign |
|---|---|---|
| **Two locks guard one handle** — `Device`'s instance monitor and `CFW11Device.driveLock` | `Device.java:27,49,73` + `CFW11Device.java:41`. The ordering rule is written out three times (`CFW11Device.java:20-33`, `:188-192`, `MotorSafetyController.java:241-257`) and enforced by nothing | Refcount moves inside the drive lock. One lock ⇒ no ordering rule, and its deadlock class stops being representable |
| **The handle escapes the lock** | **Partly addressed:** `withDrive` now hands out a `Drive` interface and `getHardwareComponent()` is gone ([`driver-api-extraction`](../virtual-devices/driver-api-extraction.md)). The reference still outlives the lambda, so the call sites remain correct by discipline | Leased per call and revoked on return — a runtime guarantee instead of a request |
| **Lifecycle implemented twice, diverging** | `CFW11Device` bounds its poll-thread join at 2 s and says why (`:89`); `LoadCellDevice.java:49` calls bare `join()` with no interrupt | One `RefCounted<H>`, two thin mappings |
| **Three near-identical `setup()` bodies** | `DestructiveTest.java:12-38`, `CyclicTest.java:16-51`, `TimeCyclicTest.java:25-54` — same eight steps, same order | One `TestRun.start()`; each program *declares* limits + a `DriveSetup` |
| **…which has already drifted** | speed→rpm is `/ 0.375` in `DestructiveTest.java:26` and `CyclicTest.java:34`, `/ 375` in `TimeCyclicTest.java:62`. See OQ-63 — this is a live defect, not just duplication | One `Rpm` conversion, one home |
| **Inheritance used for a phase change** | `TimeCyclicTest` extends `CyclicTest`, shadows `testContext` with a narrower type, copies it into `super.testContext` (`:53-56`) | Composition: a calibration phase that hands over to a cyclic phase |
| **Stop-result handling copy-pasted 4×** | `AbstractTest.java:60-69`, `TestRunnerThread.java:130-135`, `LoadCellThread.java:387-393`, `:241-248` — same two-branch shape, four hand-written wordings | One `StopReport` + one reporter |
| **Per-run state in a singleton** | `MotorSafetyController` is a `@Service` holding `stopLatched` / `motorEnergized` / `lastResult`, so `TestRunnerThread.java:72` must remember to reset it, and `lastResult` exists only to tolerate `cleanup()` running twice | A per-run `SafetyGate`; nothing to clear, and no result cache |
| **One class, four jobs** | `MotorSafetyController` = lock façade (`:65-71`) + energize gate + tier machine + incident prose (`:320-352`), ~480 lines | Four types, one job each |
| **Escalation policy in the sensor layer** | `LoadCellThread` imports `MotorSafetyController` and decides to stop the motor itself (`:386`) | Watchdog emits a `Fault`; the run supervisor decides. Removes the only upward dependency |
| **Reflective service location** | `TestRunnerFactory.java:34-65` walks `getConstructors()[0]` and parameter types, while `TestRunnerThread.java:29-34` already switches on the type string explicitly | A `Map<String, TestProgram>`. Resolves OQ-49 by deletion |

Smaller, folded into the steps that touch them: `LoadCellThread.stop()` exists but nothing calls
it — `AbstractTest.java:71` uses the weaker `setRunning(false)`; `System.gc()` in
`AbstractTest.java:102`; signals are bare ints with `signal - 1` index maths
(`TimeCyclicTest.java:83`); `TestContext.java:45` dedupes on an unsynchronised mutable int.

## The dependency rule

Dependencies point **down only**.

```
api/       Hilla @BrowserCallable + STOMP boundary
run/       TestRun, TestProgram, Phase, Signal, TestContext
safety/    SafetyGate, StopSequence, StopTier, StopReport
drive/     Drive, DriveSession, Cfw11Drive, DriveSetup, Rpm
sense/     ForceStream, ForceWatchdog, Fault
device/    RefCounted<H>
           ↓ vendor jars
```

`sense → safety` is the one edge that violates this today (see the table above). Nothing else
does — the current packages are already close to this shape.

**Enforcement is weak and must be stated as such.** The repo's gates are `script/typecheck.ps1`
and `/deck-run`; neither can see a layering violation. What holds it: package-private
constructors, and the invariant that `Cfw11Drive` is the only file naming `Cfw11` (grep-checkable
— three files name it today). A real check means ArchUnit, and therefore the repo's first test.

## Migration order

Five steps. Each is independently shippable and verifiable with `script/typecheck.ps1` plus
`/deck-run`; none needs the next one to be correct.

| # | Step | Removes | Risk |
|---|---|---|---|
| 1 | ~~`Drive` + `Cfw11Drive`, rewrite the call sites~~ — **shipped** as [`driver-api-extraction`](../virtual-devices/driver-api-extraction.md), which also made both jars optional. `DriveSession` (the revoking lease) is what remains | the escaping vendor type; the ordering rule survives | done |
| 2 | `RefCounted<H>`; both devices shrink | ~120 duplicated lines, the unbounded join. `Device.getHardwareComponent()` is already gone | low |
| 3 | Split `MotorSafetyController` → `SafetyGate` + `StopSequence` + `StopReport` | `clearStopLatch()`, the `lastResult` cache, 4× logging | **highest** — it is the safety path; behaviour-preserving only, tier semantics frozen |
| 4 | `TestProgram` + `DriveSetup` + `TestRun` | 3× `setup()`, the reflective factory, the shadowed field, `System.gc()` | medium — touches all three test types |
| 5 | Typed `Signal` + `Rpm`; watchdog → `Fault` → supervisor | int signals, the last upward dependency | medium |

Step 3 is the one that cannot be verified by the existing gates: tier 2 and tier 3 have never
been observed to run (`../virtual-devices/README.md` explains why). Either build the simulator
first and trip them on demand, or accept that step 3 ships unexercised — an explicit choice, not
an oversight.

## What stays unchanged, deliberately

- **The synchronous locked-lambda model.** An async command queue was considered and rejected:
  the driver's own `CommandChain` (`ch.rupfizupfi.usbmodbus.commandbus`, present in
  `lib/usbmodbus.jar`, unused by this app) serialises writes only, is fire-and-forget, cannot
  carry a return value or an exception, and silently drops commands its `check()` deems
  duplicate. A safety stop must know whether the motor stopped.
- **Verification against measured speed**, and the three-tier ladder's semantics.
- **Every `LoadCellThread` detector, verbatim** — including the rise-only plausibility rule at
  `LoadCellThread.java:355-363`, whose comment is load-bearing.
- The cms / Hilla CRUD layer. Untouched.

## Where to look in the code

| Concern | File |
|---|---|
| The two locks to merge | `command-deck/src/main/java/ch/rupfizupfi/deck/device/Device.java:27` |
| Drive lock + escaping handle | `command-deck/src/main/java/ch/rupfizupfi/deck/device/frequencyconverter/CFW11Device.java:41` |
| Lifecycle to deduplicate | `command-deck/src/main/java/ch/rupfizupfi/deck/device/loadcell/LoadCellDevice.java:30` |
| The four jobs to split | `command-deck/src/main/java/ch/rupfizupfi/deck/testrunner/MotorSafetyController.java:26` |
| Identical setup bodies | `command-deck/src/main/java/ch/rupfizupfi/deck/testrunner/CyclicTest.java:16` |
| Escalation policy in the sensor layer | `command-deck/src/main/java/ch/rupfizupfi/deck/testrunner/LoadCellThread.java:372` |
| Reflective factory to delete | `command-deck/src/main/java/ch/rupfizupfi/deck/testrunner/TestRunnerFactory.java:34` |

## Open questions

| OQ | Relation |
|---|---|
| **OQ-64** | Whether to adopt this redesign at all. Owner-owed: it restructures the safety path. |
| **OQ-63** | The `TimeCyclicTest` speed divisor. Found while auditing the duplicated conversion; independent of the redesign and worth fixing first. |
| **OQ-49** | `getConstructors()[0]` — resolved by step 4, by deleting the reflection. |
| **OQ-50** | Two drive handles on one device. `DriveSession.useFresh` preserves today's behaviour exactly, so this stays **open and owed**; the redesign neither fixes nor worsens it. |
| **OQ-62** | Simulated devices. Shared step 1, now shipped; see [`../virtual-devices/README.md`](../virtual-devices/README.md). |
| **OQ-51** | `stopThread()` NPE. Step 4's `TestRun` owns the lifecycle and removes the null window, but do not wait for this doc to fix it. |
