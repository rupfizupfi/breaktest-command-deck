package ch.rupfizupfi.deck.testrunner;

import ch.rupfizupfi.deck.testrunner.measure.SensorReconnector;
import ch.rupfizupfi.deck.testrunner.measure.MeasurementLoop;
import ch.rupfizupfi.deck.data.TestResult;
import ch.rupfizupfi.deck.data.TestType;
import ch.rupfizupfi.deck.device.HardwareModeInfo;
import ch.rupfizupfi.deck.filesystem.CSVStoreService;
import ch.rupfizupfi.deck.device.api.Drive;
import ch.rupfizupfi.deck.rig.RigLimits;
import ch.rupfizupfi.deck.testrunner.program.TestProgram;
import ch.rupfizupfi.deck.testrunner.run.RunEvent;
import ch.rupfizupfi.deck.testrunner.run.RunLoop;
import ch.rupfizupfi.deck.testrunner.startup.check.AbstractCheck;
import ch.rupfizupfi.deck.testrunner.startup.check.CheckFailedException;
import jakarta.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Consumer;

/**
 * Owns one run: the runner thread, which runs the run's {@link RunLoop}, the run's
 * {@link TestStateMachine}, and the operator commands. Stop, Abort and Resume commit their state
 * under the state lock on the request thread and answer at once, with the refusal texts they always
 * had; the matching event then tells the loop to run the entry action. The hold, its deadline, the
 * resume and the incident re-broadcast all happen on the loop (OQ-64 step 6).
 * <p>
 * The recovery design this implements is
 * {@code doc/dev/backend/safety-and-recovery.md}.
 */
public class TestRunnerThread {
    private static final Logger logger = LoggerFactory.getLogger(TestRunnerThread.class);

    /** How long a stop request is given before the runner is interrupted. */
    private static final long GRACEFUL_STOP_TIMEOUT_MS = 1000;
    /** And how long after the interrupt before the motor is stopped without it. */
    private static final long INTERRUPTED_STOP_TIMEOUT_MS = 2000;

    /** Grace an aborted run gets to end on its own before the runner thread is interrupted. */
    private static final long ABORT_INTERRUPT_GRACE_MS = 2000;

    /**
     * How long a Start waits for the boot de-energize before it is refused. A healthy one is a USB
     * open and three register writes; one still pending past this is wedged.
     */
    static final long BOOT_DE_ENERGIZE_WAIT_MS = 5000;
    /** {@link #BOOT_DE_ENERGIZE_WAIT_MS}, shortened by tests. */
    long bootDeEnergizeWaitMs = BOOT_DE_ENERGIZE_WAIT_MS;

    /** {@link #stopThread()} is told no operator; the Stop transition records none either. */
    private static final String OPERATOR_STOP_ACTOR = "operator";

    private final TestRunnerFactory testRunnerFactory;
    private final MotorSafetyController motorSafetyController;
    private final HardwareModeInfo hardwareModeInfo;
    private volatile boolean running = false;
    private volatile TestResult testResult;
    /** The run's devices, null outside a run; the snapshot reads the measurement loop through it. */
    private volatile RunDevices devices;
    private TestLogger testLogger;
    private volatile Thread thread;

    private volatile TestStateMachine stateMachine;
    /** The run's control loop, built before its thread starts so a Stop can always reach it. */
    private volatile RunLoop loop;

    /**
     * An Abort's motor stop, off the request thread and off the loop, which may be busy in a resume
     * or wedged in a drive call. Its only drive command is that stop; then it tells the loop.
     */
    private volatile ExecutorService stopFallback;

    /** Its own thread, so database latency never delays the run loop. */
    private volatile ExecutorService persistExecutor;

    private volatile TestStateBroadcaster broadcaster;
    private volatile GapRecorder gapRecorder;
    private volatile CSVStoreService.TestRunFiles runFiles;
    private volatile RecoveryGates gates;

    private volatile String lastReason;
    private volatile long lastSequence;

    public TestRunnerThread(TestRunnerFactory testRunnerFactory, MotorSafetyController motorSafetyController,
                            HardwareModeInfo hardwareModeInfo) {
        this.testRunnerFactory = testRunnerFactory;
        this.motorSafetyController = motorSafetyController;
        this.hardwareModeInfo = hardwareModeInfo;
    }

    /** A failure falls back to whatever specimen is mounted; it never fails the run. */
    private void prepareBench(TestLogger log) {
        try {
            testRunnerFactory.prepareBench(testResult).ifPresent(log::log);
        } catch (RuntimeException e) {
            logger.warn("bench preparation failed, keeping the mounted specimen", e);
            log.log("bench preparation failed: " + e.getMessage());
        }
    }

    /** The type as the run log has always written it: the stored string, {@code null} when unset. */
    private String typeName() {
        TestType type = testResult.testParameter == null ? null : testResult.testParameter.type;
        return type == null ? null : type.wireName();
    }

    protected void run() {
        this.loop.run();
    }

    /**
     * The STARTING entry action up to the program, on the loop thread: the run gate, the program
     * from the registry, the bench and the startup checks. The loop then runs the start sequence
     * every type shares, energize included (OQ-64 step 7).
     *
     * @param log read once at the start: stopThread() nulls the field from the operator's thread
     */
    private void start(RunLoop runLoop, TestLogger log) throws Exception {
        // Sleep for 50ms to allow the client to set up the websocket connection
        Thread.sleep(50);
        log.log("init test " + typeName());
        // Before any device is touched: a parameter the bench cannot honour ends here as a FAULT.
        // Snapshotted once, like the gates: an admin editing the rig limits mid-run moves nothing.
        RigLimits rigLimits = testRunnerFactory.rigLimits();
        RunParameters.requireRunnable(testResult.testParameter, rigLimits);
        // The registry has exactly one factory per TestType, or the context did not start.
        TestProgram program = testRunnerFactory.createProgram(testResult, rigLimits);
        RunDevices runDevices = new RunDevices(testResult.getId(), testRunnerFactory.deviceService(),
                testRunnerFactory, runFiles, gates, rigLimits, gapRecorder);
        this.devices = runDevices;
        // The loop's own reference: stopThread() nulls the field when it gives up on a wedged
        // runner, and the loop must still tear down the run it started once it unwedges.
        runLoop.attach(program, runDevices);

        // Before the startup checks: they open the load cell, which a simulated bench answers
        // with the specimen this mounts.
        prepareBench(log);
        runStartupChecks();
    }

    private void runStartupChecks() throws CheckFailedException {
        List<String> messages = new ArrayList<>();
        for (AbstractCheck check : testRunnerFactory.getStartupChecks()) {
            try {
                check.execute();
            } catch (CheckFailedException e) {
                messages.add(e.getMessage());
            }
        }

        if (!messages.isEmpty()) {
            throw new CheckFailedException("Failed to pass startup checks: " + String.join(",\n ", messages));
        }
    }

    /** After the loop's terminal entry action, on its thread: the bookkeeping that ends the run. */
    private void afterRun() {
        recordRunOutcome();
        this.devices = null;
        releaseRunScope();
        this.running = false;
    }

    /**
     * Starts a run. Synchronized because the {@code running} check below is a check-then-act: two
     * operators clicking Start at once must not both get past it and share one bench.
     */
    public synchronized void startThread(TestResult testResult) {
        if (running) {
            TestResult active = this.testResult;
            if (active != null && active.getId() != null && active.getId().equals(testResult.getId())) {
                // The same run asked twice (a remount, React StrictMode): already doing it.
                return;
            }
            throw new IllegalStateException("run " + (active == null ? "?" : active.getId())
                    + " is active on the bench; stop it before starting another");
        }
        awaitBootDeEnergize();

        try {
            this.devices = null;
            // a latch left behind by the previous run would otherwise refuse to energize
            this.motorSafetyController.clearStopLatch();
            this.testResult = testResult;
            this.lastReason = null;
            this.lastSequence = 0;
            this.testLogger = testRunnerFactory.createLogger(testResult);
            this.testLogger.begin();
            announceHardwareMode();

            long testResultId = testResult.getId();
            this.gates = testRunnerFactory.recoveryProperties().snapshot();
            this.runFiles = testRunnerFactory.createRunFiles(testResultId);
            this.gapRecorder = testRunnerFactory.createGapRecorder(testResultId, runFiles, gates);
            this.stopFallback = Executors.newSingleThreadExecutor(r -> new Thread(r, "stop-fallback"));
            this.persistExecutor = Executors.newSingleThreadExecutor(
                    r -> new Thread(r, "test-status-persist"));

            TestStateMachine machine = new TestStateMachine();
            TestLogger log = this.testLogger;
            // Before the first transition, so the first snapshot can already ask the loop.
            this.loop = new RunLoop(machine, log::log, typeName(), safety(), gates, incidents(),
                    runLoop -> start(runLoop, log), this::afterRun);
            this.stateMachine = machine;
            this.broadcaster = testRunnerFactory.createStateBroadcaster(testResultId, this::snapshot);
            // Fan-out follows registration order: onTransition first, so the broadcaster's snapshot
            // already carries this transition's reason and sequence.
            machine.addListener(this::onTransition);
            machine.addListener(broadcaster);
            machine.addListener(testRunnerFactory.createStatusPersister(
                    testResultId, persistExecutor, gates, this::gapCount, this::droppedSampleCount));
            machine.transition(TestState.STARTING, "run requested");

            this.thread = newRunnerThread(this::run);
            // Before start(): a run that ends before this line could run would leave it stuck true
            // and refuse every later Start. The catches below roll it back if start() itself fails.
            this.running = true;
            this.thread.start();
        } catch (IOException e) {
            this.running = false;
            releaseRunScope();
            throw new IllegalStateException("Could not start the run: " + e.getMessage(), e);
        } catch (RuntimeException e) {
            // Rethrown: the operator pressed Start and no run began.
            this.running = false;
            releaseRunScope();
            throw e;
        }
    }

    /** A seam so a test can run the whole run inside start() and pin the H-16 ordering above. */
    Thread newRunnerThread(Runnable body) {
        return new Thread(body, "TestRunnerThread");
    }

    /**
     * Refuses the Start while the boot de-energize is still pending: it would de-energize this run
     * mid-setup, leaving it waiting at zero force (H-13). Bounded, since this holds the start lock
     * on a request thread.
     */
    private void awaitBootDeEnergize() {
        boolean ready;
        try {
            ready = BootDeEnergizeGate.awaitReady(bootDeEnergizeWaitMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("the bench is still initialising, the start was interrupted", e);
        }
        if (!ready) {
            throw new IllegalStateException("the bench is still initialising: the boot de-energize of the"
                    + " drive has not finished after " + bootDeEnergizeWaitMs + " ms. Try again shortly;"
                    + " if this persists the drive open is wedged and the deck needs a restart.");
        }
    }

    /**
     * Names the hardware mode on every run, in the server log and the operator's test log. Not
     * configurable, so a simulated trace can never be mistaken for a real one.
     */
    private void announceHardwareMode() {
        if (hardwareModeInfo.isSimulated()) {
            String warning = "SIMULATED HARDWARE - no load cell, no drive. This run measures nothing;"
                    + " its force trace is generated by a plant model and is not material data.";
            logger.warn(warning);
            testLogger.log(warning);
        } else {
            logger.info("hardware mode: {}", hardwareModeInfo.modeName());
            testLogger.log("hardware mode: " + hardwareModeInfo.modeName());
        }
    }

    public void stopThread() {
        if (this.running) {
            // State first, so an in-flight resume sees the run leave RESUMING and cannot clear the
            // latch below; then the latch, without the hardware, so whatever the runner reaches next
            // (a startup check, setup's energize) can no longer energize the motor. Then the event,
            // its own type, so nothing queued ahead of it can swallow it (I12).
            markOperatorStop();
            motorSafetyController.latchStop("stopped by the operator");
            RunLoop currentLoop = this.loop;
            if (currentLoop != null) {
                currentLoop.accept(new RunEvent.OperatorStop(OPERATOR_STOP_ACTOR));
            }

            // Not null in the normal sequence, but a Stop against a half-failed start must still
            // stop the motor.
            Thread currentThread = this.thread;
            try {
                if (currentThread == null) {
                    logger.warn("Stop requested with no runner thread, stopping the motor directly");
                    reportStop(motorSafetyController.safeStop("stop requested with no runner thread"));
                    return;
                }

                currentThread.join(GRACEFUL_STOP_TIMEOUT_MS);
                if (currentThread.isAlive()) {
                    currentThread.interrupt();
                    currentThread.join(INTERRUPTED_STOP_TIMEOUT_MS);
                }
                if (currentThread.isAlive()) {
                    // Bounded join on the request thread: the runner may be wedged in a native call,
                    // so stop the motor from here and let the stranded thread finish on its own.
                    logger.warn("Test runner did not stop within {} ms, stopping the motor directly",
                            GRACEFUL_STOP_TIMEOUT_MS + INTERRUPTED_STOP_TIMEOUT_MS);
                    reportStop(motorSafetyController.safeStop("runner did not stop on request"));
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(e);
            } finally {
                // Closed from here too: a wedged runner never reaches its own release.
                TestLogger log = this.testLogger;
                if (log != null) {
                    log.end();
                }
                this.testLogger = null;
                this.devices = null;
            }
        }
    }

    /**
     * An operator Stop is a legitimate end of a run whose data up to that point is good, so it goes
     * RUNNING -> STOPPING and the run loop lands it in FINISHED. ABORTED is reserved for incidents,
     * including a Stop pressed during a sensor-loss incident.
     */
    private void markOperatorStop() {
        TestStateMachine machine = this.stateMachine;
        if (machine == null || machine.current().isTerminal()) {
            return;
        }

        if (machine.current().isIncident()) {
            machine.transition(TestState.ABORTED, "stopped by the operator during a sensor-loss incident");
        } else if (machine.current() == TestState.STARTING) {
            // STARTING -> STOPPING is illegal, and nothing was measured yet that FINISHED could vouch for.
            machine.transition(TestState.ABORTED, "stopped by the operator before the run started");
        } else {
            machine.transition(TestState.STOPPING, "stopped by the operator");
        }
    }

    private void reportStop(SafeStopResult result) {
        if (result.needsOperatorAttention()) {
            logger.error("Stop could not be verified: {}", result.detail());
        }
    }

    public boolean isRunning() {
        return this.running;
    }

    /** Whether the bench is running exactly this result right now. */
    public boolean isActiveRun(long testResultId) {
        TestResult active = this.testResult;
        return running && active != null && active.getId() != null && active.getId() == testResultId;
    }

    public TestResult getTestResult() {
        return testResult;
    }

    /** A complete snapshot of the run for {@code /topic/test-state}, or null when no run is active. */
    public @Nullable TestStateMessage snapshot() {
        TestStateMachine machine = this.stateMachine;
        TestResult result = this.testResult;
        if (machine == null || result == null) {
            return null;
        }

        TestState state = machine.current();
        RunLoop currentLoop = this.loop;
        // A new incident starts with no verdict. SENSOR_LOST is committed on the measurement thread,
        // before the loop's hold entry drops the previous incident's.
        SensorReconnector.Outcome outcome = currentLoop == null || state == TestState.SENSOR_LOST
                ? null : currentLoop.recoveryOutcome();
        boolean holding = state == TestState.SAFE_HOLD && currentLoop != null;
        return new TestStateMessage(state, lastReason, holding && currentLoop.canResume(),
                gapCount(), outcome == null ? 0 : outcome.attempts(), lastKnownForce(),
                holding ? currentLoop.safeHoldDeadlineEpochMillis() : null,
                result.getId(), lastSequence);
    }

    /**
     * Accepts or refuses a Resume, returning once the loop is told, so drive re-initialisation stays
     * out of the HTTP request. The outcome arrives on {@code /topic/test-state}.
     *
     * @param actor the operator, recorded on the transition
     */
    public ResumeResponse resumeTest(String actor) {
        TestStateMachine machine = this.stateMachine;
        RunLoop currentLoop = this.loop;
        if (machine == null) {
            return new ResumeResponse(false, TestState.IDLE, "no test run is active");
        }

        // Under the state lock, so it cannot race the hold expiry, an Abort or a second Resume.
        if (currentLoop == null || !machine.compareAndTransition(TestState.SAFE_HOLD, TestState.RESUMING,
                "resume accepted, re-arming the watchdog before the drive", actor, currentLoop::canResume)) {
            TestState state = machine.current();
            return new ResumeResponse(false, state, refusalDetail(state));
        }
        // The loop runs the RESUMING entry action; its queue is unbounded, so this cannot fail.
        currentLoop.accept(new RunEvent.ResumeAccepted(actor));

        return new ResumeResponse(true, TestState.RESUMING,
                "resuming; the run reports its progress on /topic/test-state");
    }

    /** Ends the run. Same request-thread contract as {@link #resumeTest(String)}. */
    public ResumeResponse abortTest(String actor) {
        TestStateMachine machine = this.stateMachine;
        if (machine == null) {
            return new ResumeResponse(false, TestState.IDLE, "no test run is active");
        }

        TestState before = machine.current();
        if (before.isTerminal()) {
            return new ResumeResponse(false, before, "the run has already ended");
        }

        String reason = "aborted by " + actor;
        if (!machine.transition(TestState.ABORTED, reason, actor)) {
            return new ResumeResponse(false, machine.current(),
                    "the run refused the abort in state " + machine.current());
        }

        RunLoop currentLoop = this.loop;
        RunEvent.Abort abort = new RunEvent.Abort(actor, reason);
        if (!postStop(() -> stopThenAbort(currentLoop, abort)) && currentLoop != null) {
            // No fallback thread left: the loop's own teardown still stops the motor.
            currentLoop.accept(abort);
        }
        return new ResumeResponse(true, TestState.ABORTED, reason);
    }

    /**
     * @param accepted whether the command was taken; when false, {@code detail} is the reason the
     *                 operator is shown
     */
    public record ResumeResponse(boolean accepted, TestState state, String detail) {
    }

    /**
     * The abort itself, on the stop-fallback thread: motor first, then the loop is told, so the
     * loop's teardown stop always follows this one. The loop may be busy - in a resume's fresh-data
     * wait, or wedged in a drive call - and the motor must not wait for it.
     */
    private void stopThenAbort(@Nullable RunLoop currentLoop, RunEvent.Abort abort) {
        SafeStopResult result = motorSafetyController.safeStop(abort.reason());
        if (result.needsOperatorAttention()) {
            logger.error("Abort requested but the motor stop could not be verified: {}", result.detail());
        }
        // Its own event type, so a Stop already queued cannot swallow it.
        if (currentLoop != null) {
            currentLoop.accept(abort);
        }

        // A runner still parked after the grace would block every later run, so interrupt it.
        Thread runner = this.thread;
        if (runner == null) {
            return;
        }
        try {
            runner.join(ABORT_INTERRUPT_GRACE_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }
        if (running && runner.isAlive()) {
            logger.warn("aborted run did not end within {} ms, interrupting the runner thread",
                    ABORT_INTERRUPT_GRACE_MS);
            runner.interrupt();
        }
    }

    /** Tracks what the broadcast snapshot reports. */
    private void onTransition(TestStateMachine.TransitionRecord record) {
        lastReason = record.reason();
        lastSequence = record.sequence();
    }

    /** Null rather than NaN, which the wire format cannot carry. */
    private @Nullable Float lastKnownForce() {
        RunDevices current = this.devices;
        MeasurementLoop cell = current == null ? null : current.measurementLoop();
        if (cell == null) {
            return null;
        }
        float force = cell.getLastForce();
        return Float.isNaN(force) ? null : force;
    }

    private String refusalDetail(TestState state) {
        if (state == TestState.SAFE_HOLD) {
            return "the run is holding, but its recovery gates refuse a resume";
        }
        return "resume is only possible from a safe hold, the run is in " + state;
    }

    /** The motor-safety calls the run loop makes. */
    private RunLoop.Safety safety() {
        MotorSafetyController safety = this.motorSafetyController;
        return new RunLoop.Safety() {
            @Override
            public SafeStopResult stop(String reason) {
                return safety.safeStop(reason);
            }

            @Override
            public void energize(Consumer<Drive> action) {
                safety.energize(action);
            }

            @Override
            public boolean withDriveUnlessLatched(Consumer<Drive> action) {
                return safety.withDriveUnlessLatched(action);
            }

            @Override
            public void withDrive(Consumer<Drive> action) {
                safety.withDrive(action);
            }

            @Override
            public void clearStopLatch() {
                safety.clearStopLatch();
            }

            @Override
            public SafeStopResult lastStopResult() {
                return safety.getLastStopResult();
            }

            @Override
            public boolean isDriveAvailable() {
                return safety.isDriveAvailable();
            }
        };
    }

    /** The run loop's view of the gap sidecar and the state broadcast. */
    private RunLoop.Incidents incidents() {
        return new RunLoop.Incidents() {
            @Override
            public int gapCount() {
                return TestRunnerThread.this.gapCount();
            }

            @Override
            public void endGap(int index, String outcome, @Nullable SensorReconnector.Outcome recovery,
                               @Nullable SafeStopResult stop) {
                GapRecorder recorder = gapRecorder;
                if (recorder == null) {
                    return;
                }
                MeasurementLoop.GateResult gate = recovery == null ? null : recovery.gate();
                recorder.endGap(index, outcome,
                        gate == null ? null : gate.firstForce(),
                        gate == null ? null : gate.drift(),
                        gate == null ? null : gate.driftFraction(),
                        recovery == null ? 0 : recovery.attempts(),
                        stop == null ? null : stop.tier().name(),
                        stop == null ? null : stop.verified());
            }

            @Override
            public void rebroadcast() {
                TestStateBroadcaster current = broadcaster;
                if (current != null) {
                    current.rebroadcast();
                }
            }
        };
    }

    private void recordRunOutcome() {
        GapRecorder recorder = this.gapRecorder;
        TestStateMachine machine = this.stateMachine;
        if (recorder == null || machine == null) {
            return;
        }
        try {
            recorder.recordRunOutcome(machine.current().name());
        } catch (Throwable t) {
            logger.error("could not record the run outcome", t);
        }
    }

    private int gapCount() {
        GapRecorder recorder = this.gapRecorder;
        return recorder == null ? 0 : recorder.gapCount();
    }

    /** 0 until the run holds its devices: STARTING is recorded before they exist. */
    private long droppedSampleCount() {
        RunDevices current = this.devices;
        return current == null ? 0 : current.droppedSampleCount();
    }

    private boolean postStop(Runnable work) {
        ExecutorService exec = this.stopFallback;
        if (exec == null) {
            return false;
        }
        try {
            exec.execute(work);
            return true;
        } catch (RejectedExecutionException e) {
            return false;
        }
    }

    /** Releases everything that belongs to one run, the run log included. */
    private void releaseRunScope() {
        // shutdown(), not shutdownNow(): an abort's stop still in flight must finish rather than be
        // interrupted mid-verification; it is bounded (I5).
        ExecutorService fallback = this.stopFallback;
        if (fallback != null) {
            fallback.shutdown();
        }

        // shutdown(), not shutdownNow(): the queued terminal save takes the run out of IN_PROGRESS.
        ExecutorService persist = this.persistExecutor;
        if (persist != null) {
            persist.shutdown();
        }

        this.stopFallback = null;
        this.persistExecutor = null;
        this.broadcaster = null;

        // end() is idempotent, so stopThread() may close the same logger from the operator's thread.
        TestLogger log = this.testLogger;
        if (log != null) {
            log.end();
        }
        this.testLogger = null;
    }
}
