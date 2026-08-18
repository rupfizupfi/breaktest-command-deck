> Branch: `dev-split` — design captured 2026-08-17. **Sketches, not code to paste.**

# Target design — the five types

Scope, defect evidence and migration order: [`README.md`](README.md). This file is only the
shapes and what each one guarantees. Signatures are illustrative; the *ownership* they express is
the design.

## Contents

- [`Drive` — the only motor vocabulary](#drive--the-only-motor-vocabulary)
- [`DriveSession` — one lock, no escaping handle](#drivesession--one-lock-no-escaping-handle)
- [`RefCounted<H>` — the lifecycle, once](#refcountedh--the-lifecycle-once)
- [`StopSequence` — escalation as data](#stopsequence--escalation-as-data)
- [`TestProgram` + `TestRun` — declare, then run](#testprogram--testrun--declare-then-run)
- [Invariants the design buys](#invariants-the-design-buys)

## `Drive` — the only motor vocabulary

```java
public interface Drive {
    void speedReference(Rpm rpm);
    void direction(Direction direction);
    void ramp(Ramp ramp);
    void outputStage(boolean enabled);      // P0682 general-enable bit
    void running(boolean started);          // P0682 start/stop bit
    void onCommunicationLoss(LossAction action);
    Rpm measuredSpeed();                    // P0002 — never the setpoint
    DriveSnapshot snapshot();               // one locked read for the dashboard
}
```

`Cfw11Drive implements Drive` becomes the **only** file naming `Cfw11` — three do today
(`CFW11Device`, `MotorSafetyController`, `AbstractTest`). Same 12-method 1:1 mapping that
[`../virtual-devices/README.md`](../virtual-devices/README.md) already scoped, so a
`SimulatedDrive` costs nothing extra once this exists.

`measuredSpeed()` naming is deliberate: the whole stop verification rests on not confusing it
with the setpoint, which reads back 0 the instant 0 is written
(`MotorSafetyController.java:404-407`).

## `DriveSession` — one lock, no escaping handle

```java
@Component
public final class DriveSession {
    private final ReentrantLock wire = new ReentrantLock();
    private final RefCounted<Cfw11Drive> handle =
            new RefCounted<>(wire, Cfw11Drive::open, Cfw11Drive::close);

    public void     use(Consumer<Drive> work);
    public <R> R    query(Function<Drive, R> work);
    public <R> R    useFresh(Function<Drive, R> work);   // tier 2: close + re-enumerate, atomic
    public void     acquire();                           // refcount, under `wire`
    public void     release();
    public boolean  isOpen();

    private <R> R locked(Function<Drive, R> work, boolean requireOpen) {
        wire.lock();
        try {
            var lease = new Lease(handle.get(requireOpen));   // delegating wrapper
            try      { return work.apply(lease); }
            finally  { lease.revoke(); }      // any later call throws, always
        } finally { wire.unlock(); }
    }
}
```

Two consequences, and they are the reason this type exists:

- Refcounting happens **under `wire`**, not under a separate instance monitor. There is then only
  one lock on the path, so the monitor-before-`driveLock` rule has nothing left to order. Today
  that rule is prose in three places and a deadlock the moment a new caller misreads it.
- `Lease.revoke()` converts *"don't stash the reference"* from `CFW11Device`'s javadoc plea into
  ~15 lines that fail loudly. (`getHardwareComponent()` has already gone from the base class.)

`useFresh` keeps `MotorSafetyController.java:242`'s behaviour exactly, including the dual-handle
question OQ-50 still owes an answer on: close, enumerate a fresh handle, command, verify, close —
all inside one `wire` hold, so a concurrent `acquire()` blocks instead of racing.

## `RefCounted<H>` — the lifecycle, once

```java
public final class RefCounted<H> {
    RefCounted(Lock lock, Supplier<H> open, Consumer<H> close);

    void acquire();          // opens on 0 → 1; a failed open rolls back to 0
    void release();          // closes on 1 → 0; clamped at zero
    void invalidate();       // handle torn down out of band (emergency re-enumeration)
    Optional<H> peek();
}
```

It **takes** a lock instead of owning one, which is what lets `DriveSession` stay single-locked
while the load cell keeps its own. Replaces `Device`'s refcount plus future
(`Device.java:21-89`) and both devices' hand-rolled open/close, keeping the three constraints
those methods learned the hard way, all currently comment-only:

| Constraint | Today |
|---|---|
| Count rises only after a successful open | `Device.java:27-43` |
| `release()` clamps at zero — cleanup runs unbalanced | `Device.java:49-54` |
| Out-of-band teardown drops the count without closing | `Device.java:73-80` |

Poll threads are **not** part of it. `DrivePoller` and the load-cell reader become explicit
components that call `query(Drive::snapshot)` / read the stream — today each device grows its own
thread, flag and start/stop pair.

## `StopSequence` — escalation as data

```java
interface StopTier {
    String name();
    StopAttempt attempt(StopContext ctx);      // returns a value; writes into no holder
}

final class StopSequence {                     // stateless singleton
    private final List<StopTier> tiers = List.of(
            new ExistingHandleStop(session),
            new FreshHandleStop(session),
            new OperatorEscalation());

    StopReport stop(String reason, RunState state);
}
```

Both real tiers are the same three moves — `StopCommands.deEnergize(Drive)`, then
`StopVerifier.awaitStandstill(Drive)`, then describe. Those two helpers already exist as
`commandStop` / `verifyStopped` (`MotorSafetyController.java:358,395`) and stay as they are; what
goes away is the plumbing around them, duplicated in `stopWithExistingHandle` and
`stopWithFreshHandle` (`:209-288`) purely because both write results out of a lambda through
`AtomicReference` holders instead of returning them.

The escalation *gate* stays exactly as documented today — a tier may only escalate when the drive
was neither responsive nor interrupted (`MotorSafetyController.java:156-159`), because a coasting
shaft on a de-energized drive is mechanics, not a dead handle.

`SafetyGate` takes the rest, per run: the latch, `energize()`'s latch-check-inside-the-lock
(`:99-111`), and the was-energized flag. Created with the run, discarded with it — so
`clearStopLatch()` and the `lastResult` replay cache both become unnecessary rather than being
reimplemented.

`StopReport` carries facts (tier, verified, responsive, rpm, detail); one reporter renders them
for all four of today's call sites.

## `TestProgram` + `TestRun` — declare, then run

```java
public interface TestProgram {
    Limits      limits(TestParameter p);
    DriveSetup  startup(TestParameter p);
    void        onSignal(Signal signal, RunControl control);
}

public record DriveSetup(Rpm speed, Direction direction, Ramp ramp, LossAction onLoss) {
    void applyTo(Drive d);        // the energize sequence, written once
}
```

```java
final class TestRun implements AutoCloseable {
    private final SafetyGate gate = new SafetyGate();      // fresh per run

    void start() {
        context = new TestContext(resultId, program.limits(param));
        force   = forceStream.watch(context);
        log.limits(context);
        force.awaitFirstSample(LOAD_CELL_STARTUP_TIMEOUT);
        drive.acquire();
        gate.energize(d -> program.startup(param).applyTo(d));
        context.pump(program);
    }
}
```

Those are the eight steps `DestructiveTest.java:12-38`, `CyclicTest.java:16-51` and
`TimeCyclicTest.java:25-54` each spell out today; each program is left declaring limits, a
`DriveSetup` and its signal handling. `AbstractTest.awaitLoadCellOrFail`'s rule survives verbatim
— energize only against a *fresh* sample, never a returned `connect()` (`AbstractTest.java:75-86`).

`TimeCyclic` stops inheriting from `Cyclic`:

```java
final class TimeCyclicProgram implements TestProgram {
    private Phase phase = new CalibrationPhase(...);   // hands over to CyclicPhase
}
```

Composition, so no shadowed `testContext`, no `super.testContext` copy and no
`super.handleSignal` fallthrough (`TimeCyclicTest.java:53-56,78`).

`TestRun.close()` is the single idempotent teardown. `AbstractTest.cleanup()`'s "can be executed
twice" (`AbstractTest.java:54-56`) is the constraint the `lastResult` cache was built for; one
owner with one guard is what retires both.

## Invariants the design buys

Each is checkable, and each replaces a comment.

| Invariant | Enforced by | Today |
|---|---|---|
| One lock per hardware resource | `DriveSession.wire` is the only lock on the drive path | two locks + a prose ordering rule in three places |
| The vendor handle cannot outlive its lock | `Lease.revoke()` | javadoc asking callers not to |
| Exactly one file names `Cfw11` | grep, `Cfw11Drive` | three files |
| Per-run state cannot leak between runs | `SafetyGate` lifetime = run lifetime | a `@Service` field plus a reset call at `TestRunnerThread.java:72` |
| One speed→rpm conversion | `Rpm` | three sites, two of them disagreeing (OQ-63) |
| One energize sequence | `DriveSetup.applyTo` | three copies |
| One stop-result rendering | `StopReport` + reporter | four |
| No layer depends upward | package-private constructors; ArchUnit if it must be real | `sense → safety` violates it |
