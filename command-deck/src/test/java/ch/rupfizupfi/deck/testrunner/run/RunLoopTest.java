package ch.rupfizupfi.deck.testrunner.run;

import ch.rupfizupfi.deck.data.TestParameter;
import ch.rupfizupfi.deck.testrunner.measure.MeasurementLoop;
import ch.rupfizupfi.deck.testrunner.RecoveryProperties;
import ch.rupfizupfi.deck.testrunner.SafeStopResult;
import ch.rupfizupfi.deck.testrunner.measure.SensorReconnector;
import ch.rupfizupfi.deck.testrunner.TestState;
import ch.rupfizupfi.deck.testrunner.TestStateMachine;
import ch.rupfizupfi.deck.device.api.Drive;
import ch.rupfizupfi.deck.testrunner.measure.ForceLimits;
import ch.rupfizupfi.deck.testrunner.measure.RunEventSink;
import ch.rupfizupfi.deck.testrunner.program.DriveSetup;
import ch.rupfizupfi.deck.testrunner.program.Limits;
import ch.rupfizupfi.deck.testrunner.program.Ramp;
import ch.rupfizupfi.deck.testrunner.program.ResumePolicy;
import ch.rupfizupfi.deck.testrunner.program.TestProgram;
import ch.rupfizupfi.deck.testrunner.program.TestPrograms;
import ch.rupfizupfi.deck.testrunner.run.RunEvent.Abort;
import ch.rupfizupfi.deck.testrunner.run.RunEvent.CeilingTripped;
import ch.rupfizupfi.deck.testrunner.run.RunEvent.LimitCrossed;
import ch.rupfizupfi.deck.testrunner.run.RunEvent.OperatorStop;
import ch.rupfizupfi.deck.testrunner.run.RunEvent.PersistenceFailed;
import ch.rupfizupfi.deck.testrunner.run.RunEvent.Recovered;
import ch.rupfizupfi.deck.testrunner.run.RunEvent.ResumeAccepted;
import ch.rupfizupfi.deck.testrunner.run.RunEvent.SensorLost;
import ch.rupfizupfi.deck.testrunner.run.RunEvent.Tick;
import ch.rupfizupfi.deck.testrunner.run.RunEvent.TimedTurn;
import ch.rupfizupfi.deck.testrunner.run.RunEvent.WatchdogFailed;
import jakarta.annotation.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import static org.awaitility.Awaitility.await;

/**
 * {@link RunLoop} against a scripted program on a real {@link TestStateMachine}. The normal-path
 * cases run the loop on the test thread with its events queued up front, and stand a request
 * thread's concurrent transition in from inside a program call, so the interleaving is fixed and
 * nothing waits on the scheduler. The incident cases run the loop on a thread of its own, as the
 * runner does, and drive it the way the measurement thread and the request threads do; their
 * races are still pinned from inside program calls. Every call the loop makes lands in one
 * {@link #calls} trace.
 */
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class RunLoopTest {

    private static final SafeStopResult VERIFIED = new SafeStopResult(SafeStopResult.Tier.EXISTING_HANDLE,
            true, true, true, 0, "standstill");
    private static final SafeStopResult FRESH_HANDLE = new SafeStopResult(SafeStopResult.Tier.FRESH_HANDLE,
            true, true, true, 0, "standstill on a fresh handle");
    private static final SensorReconnector.Outcome RECOVERED = new SensorReconnector.Outcome(true, 2,
            "recovered on attempt 2", new MeasurementLoop.GateResult(true, 12f, 1.5f, 0.01, "ok"));
    private static final String LOSS = "no measurement for more than 250 ms";
    private static final String STOP_IN_INCIDENT = "stopped by the operator during a sensor-loss incident";

    private final List<String> calls = Collections.synchronizedList(new ArrayList<>());
    private final TestStateMachine machine = new TestStateMachine();
    private final ScriptedProgram program = new ScriptedProgram();
    private final ScriptedDevices devices = new ScriptedDevices();
    private final FakeSafety safety = new FakeSafety();
    private final FakeIncidents incidents = new FakeIncidents();
    private final RecoveryProperties recovery = new RecoveryProperties();

    /** One sample of every event type the loop knows; a type added without one fails the table test. */
    private static final Map<Class<?>, RunEvent> SAMPLES = Map.ofEntries(
            Map.entry(LimitCrossed.class, new LimitCrossed(Limit.UPPER, 150f)),
            Map.entry(TimedTurn.class, new TimedTurn(Direction.PULL)),
            Map.entry(OperatorStop.class, new OperatorStop("operator")),
            Map.entry(Abort.class, new Abort("operator", "aborted by operator")),
            Map.entry(CeilingTripped.class, new CeilingTripped(13_000f, 3000.0)),
            Map.entry(WatchdogFailed.class, new WatchdogFailed("load cell thread failed: boom")),
            Map.entry(PersistenceFailed.class, new PersistenceFailed("could not write the force CSV")),
            Map.entry(SensorLost.class, new SensorLost(LOSS, 140f)),
            Map.entry(Recovered.class, new Recovered(RECOVERED)),
            Map.entry(ResumeAccepted.class, new ResumeAccepted("operator")),
            Map.entry(Tick.class, new Tick()));

    RunLoopTest() {
        machine.addListener(record -> calls.add("-> " + record.to() + " | " + record.reason()));
        machine.transition(TestState.STARTING, "run requested");
        calls.clear();
    }

    /** A program that does nothing, for the cases that override only what they observe. */
    private static class QuietProgram implements TestProgram {
        @Override
        public String name() {
            return "QuietTest";
        }

        @Override
        public Limits limits() {
            return Limits.withoutCeiling(100, 0);
        }

        @Override
        public DriveSetup startup() {
            return new DriveSetup(100, Direction.PULL, null);
        }

        @Override
        public Verdict onLimit(Limit limit, RunControl control) {
            return Verdict.CONTINUE;
        }

        @Override
        public Verdict onTimedTurn(Direction next, RunControl control) {
            return Verdict.CONTINUE;
        }

        @Override
        public ResumePolicy resumePolicy() {
            return resuming(this::resumeSetup);
        }

        /** What the policy re-energizes with; a case overrides it to observe the call. */
        DriveSetup resumeSetup(RunControl control) {
            return startup();
        }
    }

    /** A policy that resumes, on the setup {@code setup} answers. */
    private static ResumePolicy resuming(Function<RunControl, DriveSetup> setup) {
        return new ResumePolicy() {
            @Override
            public boolean supportsResume() {
                return true;
            }

            @Override
            public DriveSetup resumeSetup(RunControl control) {
                return setup.apply(control);
            }
        };
    }

    /** Devices that do nothing and always have data, for the cases that override only what they observe. */
    private static class QuietDevices implements RunLoop.Devices {
        @Override
        public void startMeasurement(ForceLimits limits, RunEventSink sink) {
        }

        @Override
        public void awaitFirstSample() {
        }

        @Override
        public void connectDrive() {
        }

        @Override
        public RunControl.Envelope envelope() {
            return new RunControl.Envelope(0, 0);
        }

        @Override
        public void resetEnvelopeMin(float value) {
        }

        @Override
        public void resetEnvelopeMax(float value) {
        }

        @Override
        public float lastForce() {
            return Float.NaN;
        }

        @Override
        public RunEventSink timedTurns() {
            return event -> { };
        }

        @Override
        public boolean startRecovery(String reason, float lastKnownForce, double envelopeNewton, RunEventSink sink) {
            return true;
        }

        @Override
        public void resetEdges() {
        }

        @Override
        public boolean freshDataFlowing(long freshTimeoutMs, long maxAgeMs) {
            return true;
        }

        @Override
        public void endHold() {
        }

        @Override
        public void teardown() {
        }

        @Override
        public void destroy() {
        }
    }

    /** What a program answers and what it does on the way, per call; defaults continue quietly. */
    private final class ScriptedProgram implements TestProgram {
        Function<Limit, Verdict> onLimit = limit -> Verdict.CONTINUE;
        Function<Direction, Verdict> onTimedTurn = next -> Verdict.CONTINUE;
        FinishCause finishedWith;
        MotorStop finishedStop;
        /** The upper limit; the drift gate's envelope is the larger limit magnitude. */
        double envelope = 100;
        Consumer<RunControl> onStart = control -> { };
        DriveSetup startup = new DriveSetup(800, Direction.RELEASE, null);
        DriveSetup resumeSetup = new DriveSetup(800, Direction.PULL, null);

        @Override
        public String name() {
            return "ScriptedTest";
        }

        @Override
        public Limits limits() {
            return Limits.withCeiling(envelope, 0, envelope * 2);
        }

        @Override
        public DriveSetup startup() {
            return startup;
        }

        @Override
        public void onStart(RunControl control) {
            calls.add("onStart");
            onStart.accept(control);
        }

        @Override
        public Verdict onLimit(Limit limit, RunControl control) {
            calls.add("onLimit " + limit);
            return onLimit.apply(limit);
        }

        @Override
        public Verdict onTimedTurn(Direction next, RunControl control) {
            calls.add("onTimedTurn " + next);
            return onTimedTurn.apply(next);
        }

        @Override
        public void onFinished(FinishCause cause, MotorStop stop, RunControl control) {
            finishedWith = cause;
            finishedStop = stop;
            calls.add("onFinished " + cause);
        }

        @Override
        public void onHoldEntry() {
            calls.add("onHoldEntry");
        }

        @Override
        public ResumePolicy resumePolicy() {
            return resuming(control -> resumeSetup);
        }
    }

    /** The run's devices, scripted: the measurement loop, the reconnector, the drive connection. */
    private final class ScriptedDevices implements RunLoop.Devices {
        RuntimeException destroyFailure;
        RuntimeException firstSampleFailure;
        /** What the reconnector worker does with the loop's sink: report a passed gate at once. */
        Consumer<RunEventSink> recovery = sink -> sink.accept(new Recovered(RECOVERED));
        BooleanSupplier freshData = () -> true;
        volatile ForceLimits limits;
        volatile RunEventSink sink;
        float lastForce = 140f;

        @Override
        public void startMeasurement(ForceLimits forceLimits, RunEventSink runSink) {
            calls.add("startMeasurement " + forceLimits.getUpperLimit() + " " + forceLimits.getLowerLimit()
                    + " " + forceLimits.getForceCeiling());
            this.limits = forceLimits;
            this.sink = runSink;
        }

        @Override
        public void awaitFirstSample() {
            calls.add("awaitFirstSample");
            if (firstSampleFailure != null) {
                throw firstSampleFailure;
            }
        }

        @Override
        public void connectDrive() {
            calls.add("connectDrive");
        }

        @Override
        public RunControl.Envelope envelope() {
            calls.add("envelope");
            return new RunControl.Envelope(10f, 90f);
        }

        @Override
        public void resetEnvelopeMin(float value) {
            calls.add("resetEnvelopeMin " + value);
        }

        @Override
        public void resetEnvelopeMax(float value) {
            calls.add("resetEnvelopeMax " + value);
        }

        @Override
        public float lastForce() {
            return lastForce;
        }

        @Override
        public RunEventSink timedTurns() {
            return sink;
        }

        @Override
        public boolean startRecovery(String reason, float lastKnownForce, double envelopeNewton, RunEventSink runSink) {
            calls.add("startRecovery " + envelopeNewton + " " + lastKnownForce);
            recovery.accept(runSink);
            return true;
        }

        @Override
        public void resetEdges() {
            calls.add("resetEdges");
        }

        @Override
        public boolean freshDataFlowing(long freshTimeoutMs, long maxAgeMs) {
            calls.add("freshData " + freshTimeoutMs + " " + maxAgeMs);
            return freshData.getAsBoolean();
        }

        @Override
        public void endHold() {
            calls.add("endHold");
        }

        @Override
        public void teardown() {
            calls.add("teardown");
        }

        @Override
        public void destroy() {
            calls.add("destroy");
            if (destroyFailure != null) {
                throw destroyFailure;
            }
        }
    }

    /** The motor-safety side: a latch and a last result, as {@code MotorSafetyController} keeps them. */
    private final class FakeSafety implements RunLoop.Safety {
        volatile boolean latched = false;
        volatile SafeStopResult lastResult;
        volatile boolean driveAvailable = true;
        /** What every stop returns. */
        volatile SafeStopResult stopResult = VERIFIED;
        /** Runs inside a resume's energize, after the drive took it: where a racing Stop lands. */
        Runnable onEnergize = () -> { };
        /** Every drive write the loop made, as the recording drive in the step-1 harness writes them. */
        final Drive drive = recordingDrive();

        @Override
        public void energize(Consumer<Drive> action) {
            if (latched) {
                calls.add("energize refused");
                throw new IllegalStateException("refusing to energize the motor, a safety stop was requested");
            }
            calls.add("energize");
            action.accept(drive);
            if (machine.current() == TestState.RESUMING) {
                onEnergize.run();
            }
        }

        @Override
        public boolean withDriveUnlessLatched(Consumer<Drive> action) {
            if (latched) {
                return false;
            }
            action.accept(drive);
            return true;
        }

        @Override
        public void withDrive(Consumer<Drive> action) {
            action.accept(drive);
        }

        @Override
        public SafeStopResult stop(String reason) {
            calls.add("stop " + reason);
            latched = true;
            lastResult = stopResult;
            return stopResult;
        }

        @Override
        public void clearStopLatch() {
            calls.add("clearStopLatch");
            latched = false;
            lastResult = null;
        }

        @Override
        public @Nullable SafeStopResult lastStopResult() {
            return lastResult;
        }

        @Override
        public boolean isDriveAvailable() {
            return driveAvailable;
        }
    }

    /** The gap sidecar as the measurement loop opens and the loop closes it, and the broadcast. */
    private final class FakeIncidents implements RunLoop.Incidents {
        volatile int gaps = 0;
        final List<String> closed = Collections.synchronizedList(new ArrayList<>());
        final AtomicInteger rebroadcasts = new AtomicInteger();

        @Override
        public int gapCount() {
            return gaps;
        }

        @Override
        public void endGap(int index, String outcome, @Nullable SensorReconnector.Outcome outcomeOfRecovery,
                           @Nullable SafeStopResult stop) {
            String line = index + " " + outcome + " attempts="
                    + (outcomeOfRecovery == null ? 0 : outcomeOfRecovery.attempts())
                    + " stopTier=" + (stop == null ? null : stop.tier());
            closed.add(line);
            calls.add("endGap " + line);
        }

        @Override
        public void rebroadcast() {
            rebroadcasts.incrementAndGet();
        }
    }

    private RunLoop loop(RunLoop.Startup startup) {
        return new RunLoop(machine, line -> calls.add("log " + line), "destructive", safety,
                recovery.snapshot(), incidents, startup, () -> calls.add("terminal"));
    }

    /** A startup that attaches the program and its devices; the loop's start sequence then energizes. */
    private RunLoop startedLoop() {
        return loop(l -> l.attach(program, devices));
    }

    /** A drive whose every call lands in {@link #calls} as {@code "drive <method>(<args>)"}. */
    private Drive recordingDrive() {
        return (Drive) java.lang.reflect.Proxy.newProxyInstance(Drive.class.getClassLoader(),
                new Class<?>[]{Drive.class}, (proxy, method, args) -> {
                    if (method.getDeclaringClass() == Object.class) {
                        return method.invoke(this, args);
                    }
                    calls.add("drive " + method.getName() + "(" + (args == null ? ""
                            : Arrays.stream(args).map(String::valueOf).collect(Collectors.joining(","))) + ")");
                    return null;
                });
    }

    /** The lines of the start sequence a started loop runs ahead of RUNNING, for the exact traces. */
    private static final List<String> START_SEQUENCE = List.of(
            "startMeasurement 100.0 0.0 200.0", "onStart", "awaitFirstSample",
            "log load cell delivering measurements", "connectDrive", "energize",
            "drive setSpeedReferenceValueAsRpm(800)", "drive setUseSecondRamp(false)",
            "drive setControlParameters(true,true,true,null,false)");

    private List<TestState> history() {
        return machine.history().stream().map(TestStateMachine.TransitionRecord::to).toList();
    }

    private long count(String prefix) {
        return calls.stream().filter(c -> c.startsWith(prefix)).count();
    }

    // ---- incident helpers: what the measurement and request threads do -------------------------

    /** Runs the loop on a thread of its own, as the runner does, and waits for RUNNING. */
    private Thread runOnItsOwnThread(RunLoop loop) {
        Thread thread = Thread.ofPlatform().name("run-loop-under-test").start(loop);
        awaitCount(TestState.RUNNING, 1);
        return thread;
    }

    /** The measurement thread's loss: stop (as its emergency stop does), open a gap, then the event. */
    private void lose(RunLoop loop, SafeStopResult stop) {
        safety.latched = true;
        safety.lastResult = stop;
        incidents.gaps++;
        loop.accept(new SensorLost(LOSS, 140f));
    }

    /** The request thread's Resume: committed under the state lock with the loop's guard, then told. */
    private boolean resume(RunLoop loop) {
        if (!machine.compareAndTransition(TestState.SAFE_HOLD, TestState.RESUMING, "resume accepted",
                "operator", loop::canResume)) {
            return false;
        }
        loop.accept(new ResumeAccepted("operator"));
        return true;
    }

    /** The request thread's Stop during an incident: ABORTED, then the event. */
    private void stopDuringIncident(RunLoop loop) {
        machine.transition(TestState.ABORTED, STOP_IN_INCIDENT);
        loop.accept(new OperatorStop("operator"));
    }

    private void awaitCount(TestState state, int occurrences) {
        await().atMost(Duration.ofSeconds(5))
                .until(() -> history().stream().filter(s -> s == state).count() >= occurrences);
    }

    private void awaitCall(String prefix) {
        await().atMost(Duration.ofSeconds(5)).until(() -> count(prefix) > 0);
    }

    private static void join(Thread thread) throws InterruptedException {
        thread.join(5_000);
        assertThat(thread.isAlive()).as("loop thread ended").isFalse();
    }

    // ---- the state x event table ---------------------------------------------------------------

    @Test
    void everyStateAndEventPairIsEitherHandledOrDropped() {
        List<Class<?>> types = Arrays.asList(RunEvent.class.getPermittedSubclasses());
        assertThat(SAMPLES.keySet()).as("a sample for every RunEvent type").containsExactlyInAnyOrderElementsOf(types);

        for (TestState state : TestState.values()) {
            for (RunEvent event : SAMPLES.values()) {
                RunLoop.Disposition disposition = RunLoop.disposition(state, event);
                boolean handled = switch (event) {
                    // Motion only while running (I16).
                    case LimitCrossed c -> state == TestState.RUNNING;
                    case TimedTurn t -> state == TestState.RUNNING;
                    // The incident events act only in the state their emitter committed.
                    case SensorLost l -> state == TestState.SENSOR_LOST || state == TestState.SAFE_HOLD;
                    case Recovered r -> state == TestState.SAFE_HOLD;
                    case ResumeAccepted r -> state == TestState.RESUMING;
                    case Tick t -> state.isIncident();
                    // Every ending event ends the run in whatever state it arrives.
                    case OperatorStop s -> true;
                    case Abort a -> true;
                    case CeilingTripped c -> true;
                    case WatchdogFailed w -> true;
                    case PersistenceFailed p -> true;
                };
                RunLoop.Disposition expected = handled ? RunLoop.Disposition.HANDLED : RunLoop.Disposition.DROPPED;
                assertThat(disposition).as("%s in %s", event.getClass().getSimpleName(), state).isEqualTo(expected);
            }
        }
    }

    /**
     * I16: the state rule is the dispatch gate. A crossing or timed turn reaching the loop during a
     * hold reaches no program code. The first crossing stands in for a sensor loss landing on the
     * measurement thread while the loop is inside the program.
     */
    @Test
    void crossingsAndTimedTurnsDuringAHoldReachNoProgramCode() {
        program.onLimit = limit -> {
            machine.transition(TestState.SENSOR_LOST, "cell gone");
            machine.transition(TestState.SAFE_HOLD, "cell gone");
            return Verdict.CONTINUE;
        };
        RunLoop loop = startedLoop();
        loop.accept(new LimitCrossed(Limit.UPPER, 150f));
        loop.accept(new LimitCrossed(Limit.LOWER, -150f));
        loop.accept(new TimedTurn(Direction.PULL));
        loop.accept(new LimitCrossed(Limit.UPPER, 151f));
        // A Stop in a hold is committed ABORTED by its request thread; this one is not, so the
        // loop's FINISHED is refused and the terminal entry settles the run.
        loop.accept(new OperatorStop("operator"));
        loop.run();

        assertThat(count("onLimit")).isEqualTo(1);
        assertThat(count("onTimedTurn")).isZero();
        assertThat(history()).containsExactly(TestState.STARTING, TestState.RUNNING, TestState.SENSOR_LOST,
                TestState.SAFE_HOLD, TestState.FAULT);
    }

    /**
     * Step 6: a crossing and a timed turn the measurement side queued before its {@code SensorLost}
     * are dropped - the loss committed SENSOR_LOST on the emitting thread, before the loop reached
     * them - and the loss behind them still opens the hold.
     */
    @Test
    void aCrossingAndATimedTurnQueuedBeforeASensorLossAreDropped() throws InterruptedException {
        RunLoop loop = startedLoop();
        program.onLimit = limit -> {
            if (count("onLimit") == 1) {
                // While the loop is busy with this crossing: two more motion events, then the loss.
                loop.accept(new LimitCrossed(Limit.LOWER, -150f));
                loop.accept(new TimedTurn(Direction.RELEASE));
                lose(loop, VERIFIED);
            }
            return Verdict.CONTINUE;
        };
        Thread thread = runOnItsOwnThread(loop);
        loop.accept(new LimitCrossed(Limit.UPPER, 150f));
        awaitCount(TestState.SAFE_HOLD, 1);
        stopDuringIncident(loop);
        join(thread);

        assertThat(count("onLimit")).isEqualTo(1);
        assertThat(count("onTimedTurn")).isZero();
    }

    /**
     * I12, I16: while STOPPING, crossings and timed turns are dropped, and the operator's Stop
     * queued behind them is not swallowed.
     */
    @Test
    void whileStoppingMotionEventsAreDroppedAndTheStopBehindThemStillEndsTheRun() {
        program.onLimit = limit -> {
            machine.transition(TestState.STOPPING, "stopped by the operator");
            return Verdict.CONTINUE;
        };
        RunLoop loop = startedLoop();
        loop.accept(new LimitCrossed(Limit.UPPER, 150f));
        loop.accept(new LimitCrossed(Limit.LOWER, -150f));
        loop.accept(new TimedTurn(Direction.RELEASE));
        loop.accept(new OperatorStop("operator"));
        loop.run();

        assertThat(count("onLimit")).isEqualTo(1);
        assertThat(count("onTimedTurn")).isZero();
        assertThat(history()).containsExactly(TestState.STARTING, TestState.RUNNING, TestState.STOPPING,
                TestState.FINISHED);
        assertThat(program.finishedWith).isEqualTo(FinishCause.OPERATOR);
    }

    @Test
    void aCrossingAfterTheRunIsRunningAgainReachesTheProgram() {
        program.onLimit = limit -> {
            if (count("onLimit") == 1) {
                machine.transition(TestState.SENSOR_LOST, "cell gone");
                machine.transition(TestState.SAFE_HOLD, "cell gone");
                machine.transition(TestState.RESUMING, "resume accepted");
                machine.transition(TestState.RUNNING, "sensor recovered, drive re-initialised");
                return Verdict.CONTINUE;
            }
            return Verdict.finish(FinishCause.CYCLES_DONE);
        };
        RunLoop loop = startedLoop();
        loop.accept(new LimitCrossed(Limit.UPPER, 150f));
        loop.accept(new LimitCrossed(Limit.LOWER, -150f));
        loop.run();

        assertThat(count("onLimit")).isEqualTo(2);
        assertThat(machine.current()).isEqualTo(TestState.FINISHED);
    }

    @Test
    void discardingMotionEventsKeepsTheStopAndAbortQueued() {
        RunLoop loop = startedLoop();
        loop.accept(new LimitCrossed(Limit.UPPER, 150f));
        loop.accept(new TimedTurn(Direction.PULL));
        loop.accept(new OperatorStop("operator"));

        loop.discardMotionEvents();
        machine.transition(TestState.RUNNING, "setup complete");
        machine.transition(TestState.STOPPING, "stopped by the operator");
        loop.run();

        assertThat(count("onLimit")).isZero();
        assertThat(count("onTimedTurn")).isZero();
        assertThat(history()).containsExactly(TestState.STARTING, TestState.RUNNING, TestState.STOPPING,
                TestState.FINISHED);
    }

    // ---- the incident path ---------------------------------------------------------------------

    /**
     * I3, I17: the measurement thread commits SENSOR_LOST before it returns from the sink; the loop
     * then runs the hold entry, commits SAFE_HOLD and only then starts the reconnector, with the
     * envelope floored. I12: a Stop during the hold ends the run ABORTED, never FINISHED, and closes
     * the gap the same way.
     */
    @Test
    void aLossHoldsInOrderAndAStopDuringTheHoldEndsAbortedNeverFinished() throws InterruptedException {
        RunLoop loop = startedLoop();
        Thread thread = runOnItsOwnThread(loop);

        lose(loop, VERIFIED);
        // Committed on this thread, before accept() returned.
        assertThat(history()).contains(TestState.SENSOR_LOST);
        awaitCall("log sensor recovery after");

        assertThat(calls).containsSubsequence("-> SENSOR_LOST | " + LOSS, "onHoldEntry",
                "-> SAFE_HOLD | " + LOSS, "startRecovery 1000.0 140.0",
                "log sensor recovery after 2 attempt(s): recovered on attempt 2");
        assertThat(loop.canResume()).isTrue();
        assertThat(loop.recoveryOutcome()).isEqualTo(RECOVERED);
        assertThat(loop.safeHoldDeadlineEpochMillis()).isNotNull();

        stopDuringIncident(loop);
        join(thread);

        assertThat(history()).containsExactly(TestState.STARTING, TestState.RUNNING, TestState.SENSOR_LOST,
                TestState.SAFE_HOLD, TestState.ABORTED);
        assertThat(program.finishedWith).isEqualTo(FinishCause.ABORT);
        assertThat(incidents.closed).containsExactly("1 ABORTED attempts=2 stopTier=EXISTING_HANDLE");
        assertThat(loop.safeHoldDeadlineEpochMillis()).isNull();
    }

    @Test
    void anEnvelopeWiderThanTheFloorIsGatedAsConfigured() throws InterruptedException {
        program.envelope = 5000;
        RunLoop loop = startedLoop();
        Thread thread = runOnItsOwnThread(loop);
        lose(loop, VERIFIED);
        awaitCall("startRecovery");
        stopDuringIncident(loop);
        join(thread);

        assertThat(calls).contains("startRecovery 5000.0 140.0");
    }

    /**
     * I10, I11: the resume entry in today's order, the latch cleared only while still RESUMING, and
     * the gap closed RESUMED naming this incident's own stop, captured before the latch clear
     * discarded it.
     */
    @Test
    void aResumeRunsItsEntryActionInOrderAndClosesTheGapWithTheIncidentsOwnStop() throws InterruptedException {
        RunLoop loop = startedLoop();
        Thread thread = runOnItsOwnThread(loop);
        lose(loop, FRESH_HANDLE);
        awaitCall("log sensor recovery after");

        assertThat(resume(loop)).isTrue();
        awaitCall("endGap");

        assertThat(calls).containsSubsequence("-> RESUMING | resume accepted", "resetEdges",
                "freshData 2000 250", "clearStopLatch", "energize",
                "-> RUNNING | sensor recovered, drive re-initialised", "endHold",
                "endGap 1 RESUMED attempts=2 stopTier=FRESH_HANDLE");
        assertThat(safety.latched).isFalse();

        machine.transition(TestState.STOPPING, "stopped by the operator");
        loop.accept(new OperatorStop("operator"));
        join(thread);
        assertThat(machine.current()).isEqualTo(TestState.FINISHED);
        // Closed once, by the resume; the terminal entry found no gap open.
        assertThat(incidents.closed).hasSize(1);
    }

    /**
     * I11, I12: a Stop committed between the ResumeAccepted and the latch clear leaves the latch
     * set and the drive de-energized: runWhileIn refuses, nothing is energized, and the gap closes
     * ABORTED rather than staying HOLDING.
     */
    @Test
    void aStopBeforeTheLatchClearLeavesTheDriveDeEnergized() throws InterruptedException {
        RunLoop loop = startedLoop();
        devices.freshData = () -> {
            stopDuringIncident(loop);
            return true;
        };
        Thread thread = runOnItsOwnThread(loop);
        lose(loop, VERIFIED);
        awaitCall("log sensor recovery after");
        assertThat(resume(loop)).isTrue();
        join(thread);

        assertThat(count("clearStopLatch")).isZero();
        assertThat(count("energize")).as("the start's energize only").isEqualTo(1);
        assertThat(safety.latched).isTrue();
        assertThat(history()).endsWith(TestState.RESUMING, TestState.ABORTED);
        assertThat(incidents.closed).containsExactly("1 ABORTED attempts=2 stopTier=EXISTING_HANDLE");
    }

    /**
     * I11, the other branch: a Stop committed between the energize and {@code transition(RUNNING)}
     * finds the drive energized, so the resume undoes it with exactly one stop of its own before the
     * Stop's teardown, and never reaches RUNNING or the hold's end.
     */
    @Test
    void aStopBetweenTheEnergizeAndRunningEndsInOneSafeStop() throws InterruptedException {
        RunLoop loop = startedLoop();
        safety.onEnergize = () -> stopDuringIncident(loop);
        Thread thread = runOnItsOwnThread(loop);
        lose(loop, VERIFIED);
        awaitCall("log sensor recovery after");
        assertThat(resume(loop)).isTrue();
        join(thread);

        assertThat(count("stop resume superseded in state ABORTED")).isEqualTo(1);
        assertThat(calls).containsSubsequence("clearStopLatch", "energize",
                "stop resume superseded in state ABORTED", "stop test cleanup");
        assertThat(count("-> RUNNING | sensor recovered")).isZero();
        assertThat(count("endHold")).isZero();
        assertThat(history()).endsWith(TestState.RESUMING, TestState.ABORTED);
        assertThat(incidents.closed).containsExactly("1 ABORTED attempts=2 stopTier=EXISTING_HANDLE");
    }

    /**
     * The hold expiry: a Tick past the deadline aborts the hold by {@code safe-hold timeout}, under
     * the state lock, motor first, then the abort's run-log line and the teardown. The loop ends by
     * itself: nobody else sends the event that would end it.
     */
    @Test
    void aTickPastTheHoldDeadlineAbortsWithTheSafeHoldTimeoutActor() throws InterruptedException {
        recovery.setSafeHoldTimeoutMillis(50);
        devices.recovery = sink -> { };
        RunLoop loop = startedLoop();
        Thread thread = runOnItsOwnThread(loop);
        lose(loop, VERIFIED);
        join(thread);

        TestStateMachine.TransitionRecord last = machine.history().getLast();
        assertThat(last.to()).isEqualTo(TestState.ABORTED);
        assertThat(last.reason()).isEqualTo("aborted by safe-hold timeout");
        assertThat(last.actor()).isEqualTo(RunLoop.SAFE_HOLD_TIMEOUT_ACTOR);
        assertThat(calls).containsSubsequence("-> SAFE_HOLD | " + LOSS, "-> ABORTED | aborted by safe-hold timeout",
                "stop aborted by safe-hold timeout", "log abort: aborted by safe-hold timeout",
                "stop test cleanup", "onFinished ABORT", "terminal");
        assertThat(incidents.closed).containsExactly("1 ABORTED attempts=0 stopTier=EXISTING_HANDLE");
    }

    /** A resumed or aborted hold is never auto-aborted later: the deadline is disarmed on exit. */
    @Test
    void aResumedHoldIsNeverAbortedByItsOldDeadline() throws InterruptedException {
        recovery.setSafeHoldTimeoutMillis(300);
        RunLoop loop = startedLoop();
        Thread thread = runOnItsOwnThread(loop);
        lose(loop, VERIFIED);
        awaitCall("log sensor recovery after");
        assertThat(resume(loop)).isTrue();
        awaitCall("endGap");
        Thread.sleep(600);

        assertThat(machine.current()).isEqualTo(TestState.RUNNING);
        assertThat(count("-> ABORTED")).isZero();
        machine.transition(TestState.STOPPING, "stopped by the operator");
        loop.accept(new OperatorStop("operator"));
        join(thread);
    }

    /**
     * A flapping link: the loss during RESUMING takes the {@code RESUMING -> SENSOR_LOST} edge, the
     * superseded resume never clears the latch nor energizes, the first gap closes SUPERSEDED and
     * the second hold starts with no verdict of its own. The loss past {@code maxLossesPerRun} then
     * refuses the resume even with a passed gate.
     */
    @Test
    void aLossDuringTheResumeSupersedesItAndTheLossPastTheBudgetIsRefused() throws InterruptedException {
        recovery.setMaxLossesPerRun(1);
        RunLoop loop = startedLoop();
        AtomicInteger freshChecks = new AtomicInteger();
        devices.freshData = () -> {
            if (freshChecks.incrementAndGet() == 1) {
                lose(loop, VERIFIED);
            }
            return true;
        };
        AtomicInteger recoveries = new AtomicInteger();
        devices.recovery = sink -> {
            if (recoveries.incrementAndGet() == 1) {
                sink.accept(new Recovered(RECOVERED));
            }
        };
        Thread thread = runOnItsOwnThread(loop);
        lose(loop, VERIFIED);
        awaitCall("log sensor recovery after");
        assertThat(resume(loop)).isTrue();
        awaitCount(TestState.SAFE_HOLD, 2);

        assertThat(count("clearStopLatch")).isZero();
        assertThat(count("energize")).as("the start's energize only").isEqualTo(1);
        assertThat(count("onHoldEntry")).isEqualTo(2);
        assertThat(machine.history()).anyMatch(r -> r.from() == TestState.RESUMING && r.to() == TestState.SENSOR_LOST);
        assertThat(incidents.closed).containsExactly("1 SUPERSEDED attempts=2 stopTier=EXISTING_HANDLE");
        assertThat(loop.recoveryOutcome()).as("the second incident's own verdict").isNull();

        // Its verdict passes, but this is the second loss of a one-loss budget.
        loop.accept(new Recovered(RECOVERED));
        await().atMost(Duration.ofSeconds(5)).until(() -> loop.recoveryOutcome() != null);
        assertThat(loop.canResume()).isFalse();
        assertThat(resume(loop)).isFalse();

        stopDuringIncident(loop);
        join(thread);
        assertThat(incidents.closed).containsExactly("1 SUPERSEDED attempts=2 stopTier=EXISTING_HANDLE",
                "2 ABORTED attempts=2 stopTier=EXISTING_HANDLE");
    }

    /** A resume that throws faults the run, stops the motor and ends the loop with no further event. */
    @Test
    void aFailedResumeFaultsTheRunAndEndsTheLoopByItself() throws InterruptedException {
        devices.freshData = () -> false;
        RunLoop loop = startedLoop();
        Thread thread = runOnItsOwnThread(loop);
        lose(loop, VERIFIED);
        awaitCall("log sensor recovery after");
        assertThat(resume(loop)).isTrue();
        join(thread);

        assertThat(machine.current()).isEqualTo(TestState.FAULT);
        assertThat(machine.history().getLast().reason()).startsWith("resume failed: java.lang.IllegalStateException");
        assertThat(count("clearStopLatch")).isZero();
        assertThat(count("energize")).as("the start's energize only").isEqualTo(1);
        String stop = "stop resume failed: " + machine.history().getLast().reason().substring("resume failed: ".length());
        assertThat(calls).containsSubsequence(stop, "endGap 1 RESUME_FAILED attempts=2 stopTier=EXISTING_HANDLE",
                "stop test cleanup", "onFinished FAULT", "terminal");
        assertThat(count("terminal")).isEqualTo(1);
    }

    /** The resume gates: the drive handle and the type's own answer refuse even a passed gate. */
    @Test
    void aDriveWithoutAHandleRefusesTheResume() throws InterruptedException {
        RunLoop loop = startedLoop();
        Thread thread = runOnItsOwnThread(loop);
        lose(loop, VERIFIED);
        awaitCall("log sensor recovery after");
        assertThat(loop.canResume()).isTrue();

        safety.driveAvailable = false;
        assertThat(loop.canResume()).isFalse();
        assertThat(resume(loop)).isFalse();
        stopDuringIncident(loop);
        join(thread);
    }

    /** {@code /topic/test-state} is re-broadcast on the loop's tick during an incident, and only then. */
    @Test
    void theIncidentIsReBroadcastOnEveryTickAndNotOnceTheRunRunsAgain() throws InterruptedException {
        RunLoop loop = startedLoop();
        loop.rebroadcastMillis = 20;
        Thread thread = runOnItsOwnThread(loop);
        lose(loop, VERIFIED);
        await().atMost(Duration.ofSeconds(5)).until(() -> incidents.rebroadcasts.get() >= 3);

        assertThat(resume(loop)).isTrue();
        awaitCall("endGap");
        int afterResume = incidents.rebroadcasts.get();
        Thread.sleep(150);
        assertThat(incidents.rebroadcasts.get()).isEqualTo(afterResume);

        machine.transition(TestState.STOPPING, "stopped by the operator");
        loop.accept(new OperatorStop("operator"));
        join(thread);
    }

    // ---- normal ends ---------------------------------------------------------------------------

    /**
     * A finish verdict in RUNNING: one stop and the teardown, then the finish hook, then FINISHED.
     * The terminal entry re-asserts the stop, as the runner's finally always did; the step-1
     * goldens pin that second de-energize write.
     */
    @Test
    void aFinishVerdictStopsTearsDownAndFinishesThenTheTerminalEntryReassertsTheStop() {
        program.onLimit = limit -> Verdict.finish(FinishCause.LIMIT);
        RunLoop loop = startedLoop();
        loop.accept(new LimitCrossed(Limit.UPPER, 150f));
        loop.run();

        List<String> expected = new ArrayList<>(START_SEQUENCE);
        expected.addAll(List.of(
                "-> RUNNING | setup complete, the motor is under force control",
                "onLimit UPPER",
                "stop test cleanup", "teardown",
                "onFinished LIMIT",
                "log ScriptedTest finishing test",
                "-> FINISHED | the test reached its end condition",
                "stop test cleanup", "teardown", "destroy",
                "terminal"));
        assertThat(calls).containsExactlyElementsOf(expected);
    }

    /** How the finish stop went reaches the finish hook, mapped off the runner's stop result. */
    @ParameterizedTest
    @EnumSource(MotorStop.class)
    void theFinishStopsOutcomeReachesTheFinishHook(MotorStop expected) {
        safety.stopResult = switch (expected) {
            case CONFIRMED -> VERIFIED;
            case COASTING -> new SafeStopResult(SafeStopResult.Tier.EXISTING_HANDLE, false, true, true, 400,
                    "coasting");
            case UNCONFIRMED -> new SafeStopResult(SafeStopResult.Tier.OPERATOR_ESCALATION, false, false, true,
                    null, "no handle answers");
            case NEVER_ENERGIZED -> new SafeStopResult(SafeStopResult.Tier.OPERATOR_ESCALATION, false, false,
                    false, null, "no handle answers");
        };
        program.onLimit = limit -> Verdict.finish(FinishCause.LIMIT);
        RunLoop loop = startedLoop();
        loop.accept(new LimitCrossed(Limit.LOWER, 50f));
        loop.run();

        assertThat(program.finishedWith).isEqualTo(FinishCause.LIMIT);
        assertThat(program.finishedStop).isEqualTo(expected);
        assertThat(RunLoop.motorStop(safety.stopResult)).isEqualTo(expected);
    }

    @Test
    void anOperatorStopInStoppingLandsInFinished() {
        RunLoop loop = startedLoop();
        machine.transition(TestState.RUNNING, "setup complete");
        machine.transition(TestState.STOPPING, "stopped by the operator");
        loop.accept(new OperatorStop("operator"));
        loop.run();

        assertThat(history()).containsExactly(TestState.STARTING, TestState.RUNNING, TestState.STOPPING,
                TestState.FINISHED);
        assertThat(program.finishedWith).isEqualTo(FinishCause.OPERATOR);
    }

    /**
     * An Abort committed ABORTED and stopped the motor before it was queued; the loop writes its
     * run-log line and unwinds it without a FINISHED.
     */
    @Test
    void anAbortUnwindsTheRunWithoutAFinishedTransition() {
        RunLoop loop = startedLoop();
        machine.transition(TestState.RUNNING, "setup complete");
        machine.transition(TestState.ABORTED, "aborted by operator", "operator");
        loop.accept(new Abort("operator", "aborted by operator"));
        loop.run();

        assertThat(history()).containsExactly(TestState.STARTING, TestState.RUNNING, TestState.ABORTED);
        assertThat(program.finishedWith).isEqualTo(FinishCause.ABORT);
        assertThat(calls).containsSubsequence("log abort: aborted by operator", "stop test cleanup",
                "log ScriptedTest finishing test");
        assertThat(count("stop ")).isEqualTo(2);
    }

    /**
     * An Abort or a Stop committed on a request thread while the loop is inside the program wins
     * over the finish verdict the program then returns: the machine refuses the later transition.
     */
    @Test
    void anAbortCommittedWhileTheProgramDecidesWinsOverItsFinishVerdict() {
        program.onLimit = limit -> {
            machine.transition(TestState.ABORTED, "aborted by operator", "operator");
            return Verdict.finish(FinishCause.LIMIT);
        };
        RunLoop loop = startedLoop();
        loop.accept(new LimitCrossed(Limit.UPPER, 150f));
        loop.run();

        assertThat(history()).containsExactly(TestState.STARTING, TestState.RUNNING, TestState.ABORTED);
        // Not a break: the destructive test's suction keys on LIMIT.
        assertThat(program.finishedWith).isEqualTo(FinishCause.ABORT);
    }

    @Test
    void aStopCommittedWhileTheProgramDecidesKeepsItsStoppingRecord() {
        program.onLimit = limit -> {
            machine.transition(TestState.STOPPING, "stopped by the operator");
            return Verdict.finish(FinishCause.CYCLES_DONE);
        };
        RunLoop loop = startedLoop();
        loop.accept(new LimitCrossed(Limit.LOWER, -150f));
        loop.run();

        assertThat(history()).containsExactly(TestState.STARTING, TestState.RUNNING, TestState.STOPPING,
                TestState.FINISHED);
        assertThat(count("onFinished")).isEqualTo(1);
    }

    // ---- detector trips ------------------------------------------------------------------------

    /** I9: a ceiling crossing ends the run FAULT, named in the history, with the stop re-asserted. */
    @Test
    void aCeilingTripEndsTheRunInFaultWithItsReasonAndTheAbortRecord() {
        RunLoop loop = startedLoop();
        machine.transition(TestState.RUNNING, "setup complete");
        CeilingTripped trip = new CeilingTripped(13_000f, 3000.0);
        loop.accept(trip);
        // Committed on the emitting thread, before the loop has even looked at its queue.
        assertThat(machine.current()).isEqualTo(TestState.FAULT);
        loop.run();

        assertThat(machine.history().getLast().reason()).isEqualTo(trip.detail());
        assertThat(calls).containsSubsequence("log " + trip.detail(), "log abort: " + trip.detail(),
                "stop test cleanup", "onFinished FAULT", "log ScriptedTest finishing test",
                "stop test cleanup", "destroy", "terminal");
    }

    @Test
    void aDeadMeasurementLoopEndsTheRunInFault() {
        RunLoop loop = startedLoop();
        machine.transition(TestState.RUNNING, "setup complete");
        loop.accept(new WatchdogFailed("load cell thread failed: boom"));
        loop.run();

        assertThat(history()).containsExactly(TestState.STARTING, TestState.RUNNING, TestState.FAULT);
        assertThat(program.finishedWith).isEqualTo(FinishCause.FAULT);
    }

    /**
     * Step 9 (A-07): a lost trace ends the run FAULT through the loop. Its emitter stopped nothing,
     * so the FAULT is committed by the loop, not on the writer thread, and the only stops are the
     * finish's own and the terminal re-assert - never an emergency stop of its own.
     */
    @Test
    void aPersistenceFailureEndsTheRunInFaultThroughTheLoopsOwnStop() {
        RunLoop loop = startedLoop();
        machine.transition(TestState.RUNNING, "setup complete");
        PersistenceFailed failure = new PersistenceFailed("could not write the force CSV run_force.csv: disk full");
        loop.accept(failure);
        // Not committed on the emitting thread: nothing was de-energized for it.
        assertThat(machine.current()).isEqualTo(TestState.RUNNING);
        loop.run();

        assertThat(history()).containsExactly(TestState.STARTING, TestState.RUNNING, TestState.FAULT);
        assertThat(machine.history().getLast().reason()).isEqualTo(failure.detail());
        assertThat(program.finishedWith).isEqualTo(FinishCause.FAULT);
        assertThat(calls).containsSubsequence("-> FAULT | " + failure.detail(), "log " + failure.detail(),
                "log abort: " + failure.detail(), "stop test cleanup", "onFinished FAULT",
                "stop test cleanup", "destroy", "terminal");
        assertThat(count("stop ")).isEqualTo(count("stop test cleanup"));
    }

    /**
     * A trip during STARTING: its stop latched the energize out, so the startup throws. The trip
     * still names the FAULT, and its run-log lines come ahead of the error's.
     */
    @Test
    void aTripThatMadeTheStartupThrowStillNamesTheFaultAndItsLogLinesComeFirst() {
        CeilingTripped trip = new CeilingTripped(3500f, 3000.0);
        RunLoop loop = loop(l -> {
            l.attach(program, devices);
            l.accept(trip);
            throw new IllegalStateException("refusing to energize the motor, a safety stop was requested");
        });
        loop.run();

        assertThat(history()).containsExactly(TestState.STARTING, TestState.FAULT);
        assertThat(machine.history().getLast().reason()).isEqualTo(trip.detail());
        assertThat(calls).containsSubsequence("log " + trip.detail(), "log abort: " + trip.detail(),
                "log error: IllegalStateException, refusing to energize the motor, a safety stop was requested",
                "log error test destructive");
    }

    /** An Abort that latched the energize out during STARTING is still owed its run-log line. */
    @Test
    void anAbortThatMadeTheStartupThrowStillLogsItsLineFirst() {
        RunLoop loop = loop(l -> {
            l.attach(program, devices);
            machine.transition(TestState.ABORTED, "aborted by operator", "operator");
            l.accept(new Abort("operator", "aborted by operator"));
            throw new IllegalStateException("refusing to energize the motor, a safety stop was requested");
        });
        loop.run();

        assertThat(history()).containsExactly(TestState.STARTING, TestState.ABORTED);
        assertThat(calls).containsSubsequence("log abort: aborted by operator",
                "log error: IllegalStateException, refusing to energize the motor, a safety stop was requested");
    }

    // ---- throws --------------------------------------------------------------------------------

    @Test
    void aThrowFromTheStartingEntryLandsInFaultWithOneStop() {
        RunLoop loop = loop(l -> {
            l.attach(program, devices);
            throw new IllegalStateException("no load cell measurement");
        });
        loop.run();

        assertThat(history()).containsExactly(TestState.STARTING, TestState.FAULT);
        assertThat(machine.history().getLast().reason()).isEqualTo("IllegalStateException: no load cell measurement");
        assertThat(calls).containsSubsequence("log error: IllegalStateException, no load cell measurement",
                "log error test destructive", "-> FAULT | IllegalStateException: no load cell measurement",
                "stop test cleanup", "teardown", "destroy", "terminal");
        assertThat(count("stop ")).isEqualTo(1);
        assertThat(count("onFinished")).isZero();
        assertThat(count("log ScriptedTest finishing test")).isZero();
    }

    @Test
    void aThrowFromProgramCodeLandsInFaultWithAStop() {
        program.onLimit = limit -> {
            throw new IllegalStateException("drive did not answer");
        };
        RunLoop loop = startedLoop();
        loop.accept(new LimitCrossed(Limit.UPPER, 150f));
        loop.run();

        assertThat(history()).containsExactly(TestState.STARTING, TestState.RUNNING, TestState.FAULT);
        assertThat(count("stop ")).isEqualTo(1);
        assertThat(count("terminal")).isEqualTo(1);
    }

    /** I21: a parameter refused before the program exists faults with no device touched, no stop. */
    @Test
    void aThrowBeforeTheProgramExistsFaultsWithoutAStop() {
        RunLoop loop = loop(l -> {
            throw new IllegalArgumentException("speed must be positive, was 0 mm/min");
        });
        loop.run();

        assertThat(history()).containsExactly(TestState.STARTING, TestState.FAULT);
        assertThat(count("stop ")).isZero();
        assertThat(count("terminal")).isEqualTo(1);
    }

    /** I1, I12: a Stop latched during STARTING refuses the energize; the run lands in ABORTED. */
    @Test
    void anEnergizeRefusedByTheLatchDuringStartingLandsInAbortedNotFault() {
        RunLoop loop = loop(l -> {
            l.attach(program, devices);
            // The operator's Stop, on its request thread: state, latch, event.
            machine.transition(TestState.ABORTED, "stopped by the operator before the run started");
            l.accept(new OperatorStop("operator"));
            throw new IllegalStateException("refusing to energize the motor, a safety stop was requested");
        });
        loop.run();

        assertThat(history()).containsExactly(TestState.STARTING, TestState.ABORTED);
        assertThat(count("stop ")).isEqualTo(1);
    }

    @Test
    void anInterruptedRunnerEndsAborted() {
        RunLoop loop = loop(l -> {
            l.attach(program, devices);
            throw new InterruptedException("stop timeout");
        });
        loop.run();

        assertThat(history()).containsExactly(TestState.STARTING, TestState.ABORTED);
        assertThat(calls).contains("log interrupt test destructive");
    }

    @Test
    void aFailingDestroyIsRetriedAndTheMotorStoppedAgain() {
        devices.destroyFailure = new IllegalStateException("USB handle gone");
        RunLoop loop = startedLoop();
        loop.accept(new WatchdogFailed("load cell thread failed: boom"));
        loop.run();

        assertThat(calls).containsSubsequence("destroy", "log error: IllegalStateException, USB handle gone",
                "destroy", "stop cleanup failed", "terminal");
    }

    // ---- once per run ----------------------------------------------------------------------

    enum Ending {
        FINISH_VERDICT, OPERATOR_STOP, ABORT, CEILING, WATCHDOG, PERSISTENCE, STARTUP_THROW, PROGRAM_THROW, INTERRUPT
    }

    @ParameterizedTest
    @EnumSource(Ending.class)
    void theTerminalEntryActionRunsExactlyOnceOnEveryExit(Ending ending) {
        program.onLimit = limit -> switch (ending) {
            case FINISH_VERDICT -> Verdict.finish(FinishCause.LIMIT);
            case PROGRAM_THROW -> throw new IllegalStateException("boom");
            default -> Verdict.CONTINUE;
        };
        RunLoop loop = loop(l -> {
            l.attach(program, devices);
            switch (ending) {
                case STARTUP_THROW -> throw new IllegalStateException("boom");
                case INTERRUPT -> throw new InterruptedException("boom");
                default -> {
                }
            }
        });
        switch (ending) {
            case FINISH_VERDICT, PROGRAM_THROW -> loop.accept(new LimitCrossed(Limit.UPPER, 150f));
            case OPERATOR_STOP -> loop.accept(new OperatorStop("operator"));
            case ABORT -> loop.accept(new Abort("operator", "aborted by operator"));
            case CEILING -> loop.accept(new CeilingTripped(13_000f, 3000.0));
            case WATCHDOG -> loop.accept(new WatchdogFailed("load cell thread failed: boom"));
            case PERSISTENCE -> loop.accept(new PersistenceFailed("could not write the force CSV"));
            default -> {
            }
        }
        loop.run();

        assertThat(count("terminal")).isEqualTo(1);
        assertThat(count("destroy")).isEqualTo(1);
        assertThat(machine.current().isTerminal()).isTrue();
        boolean threw = ending == Ending.STARTUP_THROW || ending == Ending.PROGRAM_THROW || ending == Ending.INTERRUPT;
        // The finish action's stop, re-asserted by the terminal entry; a throw skips the first.
        assertThat(count("stop ")).isEqualTo(threw ? 1 : 2);
        assertThat(count("onFinished")).isEqualTo(threw ? 0 : 1);
        // The terminal entry is the last thing the loop does.
        assertThat(calls.getLast()).isEqualTo("terminal");
    }

    // ---- threads -------------------------------------------------------------------------------

    /**
     * I8: parked on its queue, the loop holds no monitor and no lock, so the shutdown's safe stop -
     * or any other thread - never queues behind it.
     */
    @Test
    void theLoopHoldsNoLockWhileItWaitsOnItsQueue() throws InterruptedException {
        CountDownLatch running = new CountDownLatch(1);
        machine.addListener(record -> {
            if (record.to() == TestState.RUNNING) {
                running.countDown();
            }
        });
        RunLoop loop = startedLoop();
        Thread thread = Thread.ofPlatform().name("run-loop-under-test").start(loop);
        assertThat(running.await(5, TimeUnit.SECONDS)).isTrue();
        await().atMost(Duration.ofSeconds(5)).until(() -> thread.getState() == Thread.State.WAITING);

        ThreadInfo info = ManagementFactory.getThreadMXBean()
                .getThreadInfo(new long[]{thread.threadId()}, true, true)[0];
        assertThat(info.getLockedMonitors()).as("monitors held while parked").isEmpty();
        assertThat(info.getLockedSynchronizers()).as("locks held while parked").isEmpty();

        // An event from another thread wakes it at once.
        machine.transition(TestState.STOPPING, "stopped by the operator");
        loop.accept(new OperatorStop("operator"));
        thread.join(5_000);
        assertThat(thread.isAlive()).isFalse();
        assertThat(machine.current()).isEqualTo(TestState.FINISHED);
    }

    @Test
    void programCodeAndTheTeardownRunOnlyOnTheLoopThread() throws InterruptedException {
        List<String> threads = Collections.synchronizedList(new ArrayList<>());
        TestProgram recording = new QuietProgram() {
            private void note(String what) {
                threads.add(what + "@" + Thread.currentThread().getName());
            }

            @Override
            public DriveSetup startup() {
                note("startup");
                return super.startup();
            }

            @Override
            public void onStart(RunControl control) {
                note("onStart");
            }

            @Override
            public Verdict onLimit(Limit limit, RunControl control) {
                note("onLimit");
                return Verdict.finish(FinishCause.LIMIT);
            }

            @Override
            public Verdict onTimedTurn(Direction next, RunControl control) {
                note("onTimedTurn");
                control.turn(next);
                return Verdict.CONTINUE;
            }

            @Override
            public void onFinished(FinishCause cause, MotorStop stop, RunControl control) {
                note("onFinished");
            }

            @Override
            public void teardown(RunControl control) {
                note("teardown");
            }

            @Override
            public String name() {
                return "Recording";
            }
        };
        RunLoop.Devices recordingDevices = new QuietDevices() {
            private void note(String what) {
                threads.add(what + "@" + Thread.currentThread().getName());
            }

            @Override
            public void teardown() {
                note("devices teardown");
            }

            @Override
            public void destroy() {
                note("destroy");
            }
        };
        RunLoop loop = loop(l -> l.attach(recording, recordingDevices));
        Thread thread = Thread.ofPlatform().name("the-loop").start(loop);
        // Emitted from this thread, as the measurement and timer threads do.
        loop.accept(new TimedTurn(Direction.RELEASE));
        loop.accept(new LimitCrossed(Limit.UPPER, 150f));
        thread.join(5_000);

        Map<String, Long> byThread = threads.stream()
                .collect(Collectors.groupingBy(t -> t.substring(t.indexOf('@') + 1), Collectors.counting()));
        if (!byThread.keySet().equals(java.util.Set.of("the-loop"))) {
            fail("program code ran off the loop thread: " + threads);
        }
        assertThat(threads).isNotEmpty();
    }

    /** The incident's program and device calls - hold entry, resume, energize - run on the loop thread too. */
    @Test
    void theIncidentPathRunsOnlyOnTheLoopThread() throws InterruptedException {
        List<String> threads = Collections.synchronizedList(new ArrayList<>());
        TestProgram recording = new QuietProgram() {
            @Override
            public void onHoldEntry() {
                threads.add("onHoldEntry@" + Thread.currentThread().getName());
            }

            @Override
            DriveSetup resumeSetup(RunControl control) {
                threads.add("resumeSetup@" + Thread.currentThread().getName());
                return super.resumeSetup(control);
            }
        };
        RunLoop.Devices recordingDevices = new QuietDevices() {
            private void note(String what) {
                threads.add(what + "@" + Thread.currentThread().getName());
            }

            @Override
            public boolean startRecovery(String reason, float lastKnownForce, double envelopeNewton, RunEventSink sink) {
                note("startRecovery");
                // The worker's verdict, from a thread of its own.
                Thread.ofPlatform().start(() -> sink.accept(new Recovered(RECOVERED)));
                return true;
            }

            @Override
            public void resetEdges() {
                note("resetEdges");
            }

            @Override
            public void endHold() {
                note("endHold");
            }
        };
        RunLoop loop = loop(l -> l.attach(recording, recordingDevices));
        Thread thread = Thread.ofPlatform().name("the-loop").start(loop);
        awaitCount(TestState.RUNNING, 1);
        lose(loop, VERIFIED);
        await().atMost(Duration.ofSeconds(5)).until(loop::canResume);
        assertThat(resume(loop)).isTrue();
        awaitCount(TestState.RUNNING, 2);
        await().atMost(Duration.ofSeconds(5)).until(() -> threads.stream().anyMatch(t -> t.startsWith("endHold")));
        machine.transition(TestState.STOPPING, "stopped by the operator");
        loop.accept(new OperatorStop("operator"));
        join(thread);

        assertThat(threads).extracting(t -> t.substring(0, t.indexOf('@')))
                .containsExactly("onHoldEntry", "startRecovery", "resetEdges", "resumeSetup", "endHold");
        assertThat(threads).allMatch(t -> t.endsWith("@the-loop"));
        // The resume's energize is the loop's, on the loop thread, after the latch clear.
        assertThat(calls).containsSubsequence("clearStopLatch", "energize");
    }

    // ---- the start sequence and RunControl (OQ-64 step 7) --------------------------------------

    /**
     * The STARTING entry of every type, in order: the program's limits to the measurement loop, its
     * start lines, the first sample at the device and in the loop, the drive, and the program's
     * setup as the one energize (I10, D3) - one control word, after the speed and the ramp.
     */
    @Test
    void theStartSequenceRunsInOrderAndEnergizesTheProgramsSetupInOneControlWord() {
        program.startup = new DriveSetup(640, Direction.PULL, new Ramp(15, 20));
        machine.transition(TestState.STOPPING, "noop");
        RunLoop loop = startedLoop();
        loop.accept(new OperatorStop("operator"));
        loop.run();

        assertThat(calls).containsSubsequence("startMeasurement 100.0 0.0 200.0", "onStart",
                "awaitFirstSample", "log load cell delivering measurements", "connectDrive", "energize",
                "drive setSpeedReferenceValueAsRpm(640)", "drive setSecondSpeedRampTime(15,20)",
                "drive setControlParameters(true,true,false,null,true)");
        assertThat(count("drive setStart")).isZero();
        assertThat(count("drive setGeneralEnable")).isZero();
        assertThat(loop.direction()).isEqualTo(Direction.PULL);
    }

    /** I10: no energize before both first-sample waits pass; the run faults with one stop. */
    @Test
    void noEnergizeBeforeTheFirstSampleWaitsPass() {
        devices.firstSampleFailure = new IllegalStateException("the force watchdog saw no measurement within 2000 ms");
        RunLoop loop = startedLoop();
        loop.run();

        assertThat(count("energize")).isZero();
        assertThat(count("connectDrive")).isZero();
        assertThat(count("drive ")).isZero();
        assertThat(history()).containsExactly(TestState.STARTING, TestState.FAULT);
        assertThat(calls).containsSubsequence("awaitFirstSample",
                "log error: IllegalStateException, the force watchdog saw no measurement within 2000 ms",
                "stop test cleanup", "teardown", "destroy", "terminal");
    }

    /** I1, I12: a stop latched during STARTING refuses the start's energize. */
    @Test
    void aLatchedStopRefusesTheStartsEnergize() {
        program.onStart = control -> {
            machine.transition(TestState.ABORTED, "stopped by the operator before the run started");
            safety.latched = true;
        };
        RunLoop loop = startedLoop();
        loop.accept(new OperatorStop("operator"));
        loop.run();

        assertThat(calls).contains("energize refused");
        assertThat(count("drive set")).isZero();
        assertThat(history()).containsExactly(TestState.STARTING, TestState.ABORTED);
    }

    /**
     * Step 6's latched-turn rule, now {@code RunControl#turn}: a reversal while a stop is latched
     * touches no drive and keeps the commanded direction; an accepted one is one write, cached.
     */
    @Test
    void aTurnWhileAStopIsLatchedTouchesNoDriveAndKeepsTheCommandedDirection() {
        program.onLimit = limit -> Verdict.CONTINUE;
        RunLoop loop = startedLoop();
        machine.transition(TestState.STOPPING, "noop");
        loop.accept(new OperatorStop("operator"));
        loop.run();
        assertThat(loop.direction()).isEqualTo(Direction.RELEASE);
        calls.clear();

        safety.latched = true;
        assertThat(loop.turn(Direction.PULL)).isFalse();
        assertThat(calls).noneMatch(c -> c.startsWith("drive "));
        assertThat(loop.direction()).isEqualTo(Direction.RELEASE);

        safety.latched = false;
        assertThat(loop.turn(Direction.PULL)).isTrue();
        assertThat(calls).containsExactly("drive setDirection(false)");
        assertThat(loop.direction()).isEqualTo(Direction.PULL);
    }

    /** I13: an adaptation reaches the measurement loop only once validated; the ceiling stays. */
    @Test
    void adaptedLimitsReachTheMeasurementLoopOnlyWhenFinite() {
        RunLoop loop = startedLoop();
        machine.transition(TestState.STOPPING, "noop");
        loop.accept(new OperatorStop("operator"));
        loop.run();

        loop.adaptLimits(90, 5);
        assertThat(devices.limits.getUpperLimit()).isEqualTo(90.0);
        assertThat(devices.limits.getLowerLimit()).isEqualTo(5.0);
        assertThat(devices.limits.getForceCeiling()).isEqualTo(200.0);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> loop.adaptLimits(Double.NaN, 5))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(devices.limits.getUpperLimit()).isEqualTo(90.0);
        assertThat(loop.limits()).isEqualTo(new Limits(90, 5, 200));
    }

    /** A time-cyclic run phase changes speed and ramp on a running drive without touching start or enable. */
    @Test
    void theRunPhaseSpeedWritesRampThenSpeedAndNoControlWord() {
        RunLoop loop = startedLoop();
        machine.transition(TestState.STOPPING, "noop");
        loop.accept(new OperatorStop("operator"));
        loop.run();
        calls.clear();
        safety.latched = false;

        loop.runPhaseSpeed(266, new Ramp(10, 10));
        loop.runPhaseSpeed(266, null);
        loop.disableSecondRamp();

        assertThat(calls).containsExactly("drive setSecondSpeedRampTime(10,10)",
                "drive setSpeedReferenceValueAsRpm(266)", "drive setUseSecondRamp(false)",
                "drive setSpeedReferenceValueAsRpm(266)", "drive setUseSecondRamp(false)");
    }

    /**
     * BL-122: a phase setpoint goes through the same latch check and stop-epoch fence as a reversal,
     * so one the loop was about to send when a stop latched never reaches the drive after it.
     */
    @Test
    void aRunPhaseSpeedWhileAStopIsLatchedTouchesNoDrive() {
        RunLoop loop = startedLoop();
        machine.transition(TestState.STOPPING, "noop");
        loop.accept(new OperatorStop("operator"));
        loop.run();
        calls.clear();

        safety.latched = true;
        loop.runPhaseSpeed(266, new Ramp(10, 10));
        loop.runPhaseSpeed(266, null);

        assertThat(calls).noneMatch(c -> c.startsWith("drive "));
    }

    // ---- time-cyclic as phases (OQ-64 step 8) --------------------------------------------------

    /** A time-cyclic run between 10 N and 100 N, whose calibration reads {@code clock}. */
    private static TestProgram timeCyclic(AtomicLong clock) {
        TestParameter parameter = new TestParameter();
        parameter.speed = 50;
        parameter.upperTurnForce = 0.1;
        parameter.lowerTurnForce = 0.01;
        parameter.upperShutOffThreshold = 3.0;
        parameter.cycleCount = 10;
        parameter.startRampSeconds = 0.0;
        parameter.stopRampSeconds = 0.0;
        return TestPrograms.timeCyclic(parameter, clock::get);
    }

    /** What reaches time-cyclic program code that moves the motor: a calibration or a cyclic turn. */
    private long motionLines() {
        return count("log <b>init:") + count("log change direction");
    }

    /**
     * H-15, I17 by construction: hold entry, timed turns and the resume all run on the loop thread.
     * The hold entry closes the timed phase, so the turn it had scheduled never fires; a stale timed
     * turn during the hold is dropped by the state rule; the resume re-enters calibration on the
     * calibration setup, and a stale timed turn reaching the resumed run is dropped by calibration,
     * which schedules nothing. ScriptedDevices' envelope (10, 90) reaches both turn forces.
     */
    @Test
    void aTimeCyclicHoldDropsEveryTimedTurnAndTheResumeRecalibratesOnTheCalibrationSetup() throws InterruptedException {
        AtomicLong clock = new AtomicLong(1_000_000);
        RunLoop loop = loop(l -> l.attach(timeCyclic(clock), devices));
        Thread thread = runOnItsOwnThread(loop);

        // Calibration: three timed turns a second apart measure both legs, the fourth hands over.
        loop.accept(new LimitCrossed(Limit.UPPER, 100f));
        await().atMost(Duration.ofSeconds(5)).until(() -> count("log <b>init:") == 1);
        clock.addAndGet(1000);
        loop.accept(new LimitCrossed(Limit.LOWER, 10f));
        await().atMost(Duration.ofSeconds(5)).until(() -> count("log <b>init:") == 2);
        clock.addAndGet(1000);
        loop.accept(new LimitCrossed(Limit.UPPER, 100f));
        await().atMost(Duration.ofSeconds(5)).until(() -> count("log <b>init:") == 3);
        loop.accept(new LimitCrossed(Limit.LOWER, 10f));
        awaitCall("log Adapted Pull time: 1000 ms");

        // The timed phase's first turn schedules a timed pull a second out; the loss comes first.
        loop.accept(new LimitCrossed(Limit.UPPER, 100f));
        awaitCall("log change direction to forward");
        long scheduledAt = System.nanoTime();
        lose(loop, VERIFIED);
        awaitCount(TestState.SAFE_HOLD, 1);
        long motionBeforeHold = motionLines();

        // A stale timed turn during the hold, and the window the scheduled one would have fired in.
        loop.accept(new TimedTurn(Direction.PULL));
        await().atMost(Duration.ofSeconds(5)).until(loop::canResume);
        await().atMost(Duration.ofSeconds(5))
                .until(() -> System.nanoTime() - scheduledAt > TimeUnit.MILLISECONDS.toNanos(1500));
        assertThat(motionLines()).isEqualTo(motionBeforeHold);

        int resumeFrom = calls.size();
        assertThat(resume(loop)).isTrue();
        awaitCount(TestState.RUNNING, 2);
        awaitCall("endHold");
        // Re-energized on the calibration setup: analyse speed, short second ramp, releasing.
        assertThat(calls.subList(resumeFrom, calls.size())).containsSubsequence("clearStopLatch", "energize",
                "drive setSpeedReferenceValueAsRpm(133)", "drive setSecondSpeedRampTime(3,3)",
                "drive setControlParameters(true,true,true,null,true)");

        // A stale timed turn reaching the resumed run moves nothing; the crossing behind it calibrates.
        long directionWrites = count("drive setDirection");
        loop.accept(new TimedTurn(Direction.PULL));
        loop.accept(new LimitCrossed(Limit.UPPER, 100f));
        await().atMost(Duration.ofSeconds(5)).until(() -> count("log <b>init:") == 4);
        assertThat(count("drive setDirection")).isEqualTo(directionWrites + 1);
        assertThat(motionLines()).isEqualTo(motionBeforeHold + 1);

        machine.transition(TestState.STOPPING, "stopped by the operator");
        loop.accept(new OperatorStop("operator"));
        join(thread);
        assertThat(machine.current()).isEqualTo(TestState.FINISHED);
    }
}
