package ch.rupfizupfi.deck.testrunner;

import ch.rupfizupfi.deck.data.TestResult;
import ch.rupfizupfi.deck.device.HardwareModeInfo;
import ch.rupfizupfi.deck.device.loadcell.LoadCellDevice;
import ch.rupfizupfi.deck.filesystem.CSVStoreService;
import jakarta.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Owns one run: the runner thread itself, the run's {@link TestStateMachine}, and the two operator
 * commands - Resume and Abort - that can reach a run that is holding after a sensor loss.
 * <p>
 * The recovery design this implements is
 * {@code doc/06-feature-work/testrunner-safety/loadcell-recovery-design.md}.
 */
public class TestRunnerThread {
    private static final Logger logger = LoggerFactory.getLogger(TestRunnerThread.class);

    /** How long a stop request is given before the runner is interrupted. */
    private static final long GRACEFUL_STOP_TIMEOUT_MS = 1000;
    /** And how long after the interrupt before the motor is stopped without it. */
    private static final long INTERRUPTED_STOP_TIMEOUT_MS = 2000;

    /** Fresh-data proof demanded on the run-control thread before the drive is re-enabled. */
    private static final long RESUME_FRESH_DATA_TIMEOUT_MS = 2000;
    /**
     * Same age the watchdog itself trips on, so a resume can never pass on a stream the watchdog
     * would immediately reject.
     */
    private static final long RESUME_DATA_FLOW_MAX_AGE_MS = 250;
    /** Grace an aborted run gets to end on its own before the runner thread is interrupted. */
    private static final long ABORT_INTERRUPT_GRACE_MS = 2000;

    /** Actor recorded for the transitions the server made on its own, with no operator behind them. */
    private static final String SAFE_HOLD_TIMEOUT_ACTOR = "safe-hold timeout";

    private final TestRunnerFactory testRunnerFactory;
    private final MotorSafetyController motorSafetyController;
    private final HardwareModeInfo hardwareModeInfo;
    private volatile boolean running = false;
    private volatile TestResult testResult;
    private volatile AbstractTest test;
    private TestLogger testLogger;
    private volatile Thread thread;

    private volatile TestStateMachine stateMachine;

    /**
     * Single-threaded, and that is the requirement rather than a sizing choice: it serves the
     * SAFE_HOLD auto-abort timer, the incident re-broadcast ticker and the resume/abort commands, so
     * a second thread would let a resume re-energize the drive while the abort that supersedes it is
     * running.
     */
    private volatile ScheduledExecutorService runControl;

    /**
     * Separate from {@link #runControl} on purpose: {@link TestResultStatusPersister} exists to keep
     * database latency off the incident path, and putting its saves on the executor that fires the
     * safe-hold timer would put a remote Postgres connect timeout in front of a safety action.
     */
    private volatile ExecutorService persistExecutor;

    private volatile TestStateBroadcaster broadcaster;
    private volatile GapRecorder gapRecorder;
    private volatile CSVStoreService.TestRunFiles runFiles;
    private volatile RecoveryGates gates;

    /** The reconnector's verdict, handed over by the loss path; see {@link #recordRecoveryOutcome}. */
    private volatile SensorReconnector.Outcome lastRecoveryOutcome;

    /**
     * How the incident's safe stop went, captured on the resume path before {@code clearStopLatch()}
     * discards it. Null until a resume reads them, which is the only moment they are still knowable.
     */
    private volatile String incidentStopTier;
    private volatile Boolean incidentStopVerified;

    private volatile String lastReason;
    private volatile long lastSequence;

    /** Epoch millis FOR THE BROWSER; the server's own hold timer is a scheduled delay, not a clock. */
    private volatile Long safeHoldDeadlineEpochMillis;

    /** Guards {@link #safeHoldTimer} and its deadline, which are armed and cancelled from any thread. */
    private final Object holdTimerLock = new Object();
    private ScheduledFuture<?> safeHoldTimer;

    public TestRunnerThread(TestRunnerFactory testRunnerFactory, MotorSafetyController motorSafetyController,
                            HardwareModeInfo hardwareModeInfo) {
        this.testRunnerFactory = testRunnerFactory;
        this.motorSafetyController = motorSafetyController;
        this.hardwareModeInfo = hardwareModeInfo;
    }

    protected void run() {
        // Read once: stopThread() nulls the field from the operator's thread. Non-null here because
        // startThread assigns it before Thread.start().
        TestLogger log = this.testLogger;
        try {
            // Sleep for 50ms to allow the client to set up the websocket connection
            Thread.sleep(50);
            log.log("init test " + testResult.testParameter.type);
            test = switch (testResult.testParameter.type) {
                case "cyclic" -> testRunnerFactory.createTestRunner(CyclicTest.class, testResult, log);
                case "timeCyclic" -> testRunnerFactory.createTestRunner(TimeCyclicTest.class, testResult, log);
                case "destructive" -> testRunnerFactory.createTestRunner(DestructiveTest.class, testResult, log);
                default -> throw new IllegalArgumentException(
                        "unknown test parameter type: " + testResult.testParameter.type);
            };

            test.initRecovery(this, stateMachine, gapRecorder, runFiles, gates);
            test.runStartupChecks();
            test.setup();
            // Only after setup(), because the reconnector is built around the run's LoadCellThread
            // and that does not exist until setup() creates it. A run that never gets one still
            // holds and aborts safely; it simply can never be resumed.
            test.setReconnector(testRunnerFactory.createReconnector(
                    test.deviceService.getLoadCell(), test.loadCellThread, gates));
            settle(TestState.RUNNING, "setup complete, the motor is under force control");
            test.getContext().processSignals();
        } catch (InterruptedException e) {
            log.log("interrupt test " + testResult.testParameter.type);
            logger.error("TestRunner Thread interrupted", e);
            settle(TestState.ABORTED, "the runner thread was interrupted");
        } catch (FinishTestException ignored) {
            settle(TestState.FINISHED, "the test reached its end condition");
        } catch (Exception e) {
            // getClass() alone renders as "class java.lang.IllegalStateException" in the test log
            log.log("error: " + e.getClass().getSimpleName() + ", " + e.getMessage());
            log.log("error test " + testResult.testParameter.type);
            logger.error("Exception occurred during test", e);
            // FAULT, not ABORTED: the run died on its own. ABORTED means somebody - an operator or
            // the safe-hold timer - decided to end it, and conflating the two makes the result table
            // useless for telling a bench problem from an operator decision.
            settle(TestState.FAULT, e.getClass().getSimpleName() + ": " + e.getMessage());
        } finally {
            if (test != null) {
                try {
                    test.cleanup();
                    test.destroy();
                } catch (Exception e) {
                    log.log("error: " + e.getClass().getSimpleName() + ", " + e.getMessage());
                    logger.error("Exception during cleanup/destroy", e);
                    retryShutdownOnException();
                }
            }
            // Every exit reaches a terminal state: a run left claiming STARTING or RUNNING is swept
            // as an orphan on the next boot.
            settle(TestState.FAULT, "the run ended without reaching a terminal state");
            recordRunOutcome();
            this.test = null;
            releaseRunScope();
            this.running = false;
        }
    }

    /**
     * Starts a run. Synchronized because the {@code running} check below is a check-then-act: two
     * operators clicking Start at once must not both get past it and share one bench.
     */
    public synchronized void startThread(TestResult testResult) {
        if (running) {
            return;
        }

        try {
            this.test = null;
            // a latch left behind by the previous run would otherwise refuse to energize
            this.motorSafetyController.clearStopLatch();
            this.testResult = testResult;
            this.lastRecoveryOutcome = null;
            this.incidentStopTier = null;
            this.incidentStopVerified = null;
            this.lastReason = null;
            this.lastSequence = 0;
            this.safeHoldDeadlineEpochMillis = null;
            this.testLogger = testRunnerFactory.createLogger(testResult);
            this.testLogger.begin();
            announceHardwareMode();

            long testResultId = testResult.getId();
            this.gates = testRunnerFactory.recoveryProperties().snapshot();
            this.runFiles = testRunnerFactory.createRunFiles(testResultId);
            this.gapRecorder = testRunnerFactory.createGapRecorder(testResultId, runFiles, gates);
            this.runControl = Executors.newSingleThreadScheduledExecutor(
                    r -> new Thread(r, "test-run-control"));
            this.persistExecutor = Executors.newSingleThreadExecutor(
                    r -> new Thread(r, "test-status-persist"));

            this.stateMachine = new TestStateMachine();
            this.broadcaster = testRunnerFactory.createStateBroadcaster(testResultId, runControl, this::snapshot);
            // Registration order is the fan-out order: this one first, so the reason and sequence the
            // broadcaster's snapshot reads are already those of the transition being announced.
            this.stateMachine.addListener(this::onTransition);
            this.stateMachine.addListener(broadcaster);
            this.stateMachine.addListener(testRunnerFactory.createStatusPersister(
                    testResultId, persistExecutor, gates, this::gapCount, this::droppedSampleCount));
            this.stateMachine.transition(TestState.STARTING, "run requested");

            this.thread = new Thread(this::run, "TestRunnerThread");
            this.thread.start();
            // Last, and only once a thread exists to clear it again. Set any earlier, a failure
            // between here and there leaves the service reporting a run that nothing will ever end.
            this.running = true;
        } catch (IOException e) {
            this.running = false;
            releaseRunScope();
            throw new IllegalStateException("Could not start the run: " + e.getMessage(), e);
        } catch (RuntimeException e) {
            // Nothing is running, so the run-scoped executors and their threads must not survive.
            // Rethrown rather than swallowed: the operator pressed Start and no run began.
            this.running = false;
            releaseRunScope();
            throw e;
        }
    }

    /**
     * Names the hardware mode on every single run, to the server log and to the operator's test log.
     * Unconditional and not configurable: an annunciation an operator can turn off is one that will
     * be off on the day a simulated trace is mistaken for a real one.
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
            markOperatorStop();

            // test is still null during the startup window and after a failed setup(),
            // the stop must not throw there
            AbstractTest currentTest = this.test;
            TestContext currentContext = currentTest != null ? currentTest.getContext() : null;
            if (currentContext != null) {
                currentContext.sendSignal(0);
            }

            // Read once into a local. `running` is only set after the thread starts, so null is not
            // reachable through the normal sequence - but a Stop pressed against a half-failed start
            // must answer with a motor stop, not with an NPE on top of the failure it is reacting to.
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
                    // Bounded, because this runs on the request thread serving the operator's Stop
                    // button. An unbounded join here waits on a runner that may itself be blocked
                    // behind a device monitor held by a wedged native call - so the one control that
                    // must always answer would hang instead. The motor takes priority over the
                    // bookkeeping: stop it from here and let the stranded thread finish on its own.
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
                this.test = null;
            }
        }
    }

    /**
     * An operator Stop is a legitimate end of a run whose data up to that point is good, so it goes
     * RUNNING -> STOPPING and lands in FINISHED via the runner's own FinishTestException. ABORTED is
     * reserved for incidents. The one exception is a Stop pressed during a sensor-loss incident:
     * that run never reached its end condition and its trace has a hole in it, so it is ABORTED.
     */
    private void markOperatorStop() {
        TestStateMachine machine = this.stateMachine;
        if (machine == null || machine.current().isTerminal()) {
            return;
        }

        if (machine.current().isIncident()) {
            machine.transition(TestState.ABORTED, "stopped by the operator during a sensor-loss incident");
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
        SensorReconnector.Outcome outcome = this.lastRecoveryOutcome;
        return new TestStateMessage(state, lastReason, state == TestState.SAFE_HOLD && canResumeNow(),
                gapCount(), outcome == null ? 0 : outcome.attempts(), lastKnownForce(),
                state == TestState.SAFE_HOLD ? safeHoldDeadlineEpochMillis : null,
                result.getId(), lastSequence);
    }

    /**
     * An incident starts with no verdict; the reconnector's arrives through
     * {@link #recordRecoveryOutcome}.
     */
    void beginIncident() {
        this.lastRecoveryOutcome = null;
    }

    /**
     * Hands the reconnector's verdict to the run controller. The sensor-loss path owns the
     * reconnector; the resume that may follow needs its gate numbers for the gap record, and the
     * incident broadcast needs its attempt count.
     */
    public void recordRecoveryOutcome(SensorReconnector.Outcome outcome) {
        this.lastRecoveryOutcome = outcome;
    }

    /**
     * Accepts or refuses a Resume. Returns as soon as the work is posted to {@link #runControl}:
     * re-initialising the drive here would put the whole {@code verifyStopped} deadline, or a wedged
     * native USB call, inside an HTTP request. The browser learns the outcome from
     * {@code /topic/test-state}.
     *
     * @param actor the operator, recorded on the transition - a resume is an accountable decision
     */
    public ResumeResponse resumeTest(String actor) {
        TestStateMachine machine = this.stateMachine;
        if (machine == null) {
            return new ResumeResponse(false, TestState.IDLE, "no test run is active");
        }

        // The guard runs under the state lock, so it cannot race the safe-hold auto-abort timer or a
        // second browser that clicked Resume a millisecond earlier.
        if (!machine.compareAndTransition(TestState.SAFE_HOLD, TestState.RESUMING,
                "resume accepted, re-arming the watchdog before the drive", actor, this::canResumeNow)) {
            TestState state = machine.current();
            return new ResumeResponse(false, state, refusalDetail(state));
        }

        if (!post(() -> performResume(actor))) {
            machine.transition(TestState.FAULT,
                    "resume could not be scheduled, the run control executor is gone", actor);
            return new ResumeResponse(false, machine.current(),
                    "the run is no longer accepting commands");
        }

        return new ResumeResponse(true, TestState.RESUMING,
                "resuming; the run reports its progress on /topic/test-state");
    }

    /** Ends the run deliberately. Same request-thread contract as {@link #resumeTest(String)}. */
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

        // The motor work is posted even if the executor is gone by now - the state is already ABORTED
        // and the run's own teardown stops the motor, so a rejected post costs the gap record, not
        // the stop.
        post(() -> performAbort(reason, before.isIncident()));
        return new ResumeResponse(true, TestState.ABORTED, reason);
    }

    /**
     * @param accepted whether the command was taken; when false, {@code detail} is the reason the
     *                 operator is shown - a refused Resume must never look like an optimistic yes
     */
    public record ResumeResponse(boolean accepted, TestState state, String detail) {
    }

    /**
     * The resume itself, on the run control thread. Order is load bearing throughout and is the
     * sequence documented in {@code loadcell-recovery-design.md}.
     */
    private void performResume(String actor) {
        AbstractTest current = this.test;
        TestStateMachine machine = this.stateMachine;
        if (current == null || machine == null) {
            settle(TestState.FAULT, "the run disappeared while a resume was in flight");
            return;
        }

        try {
            TestContext context = current.getContext();

            // First, because lastSendSignal still holds the pre-loss value: without this the first
            // post-resume limit crossing is filtered as a duplicate and the motor keeps pulling with
            // nothing acting on the signal.
            context.resetSignalDedup();
            // Dispatch is still disabled here, so this only discards what queued up during the hold.
            context.drainSignals();

            LoadCellDevice loadCell = current.deviceService.getLoadCell();
            // Re-checked here rather than trusting the gate the reconnector passed milliseconds ago
            // on another thread: the watchdog has to be armed on live data BEFORE the drive is
            // re-enabled, and between the gate and this point the link may have dropped again.
            if (!loadCell.awaitFreshMeasurement(RESUME_FRESH_DATA_TIMEOUT_MS)
                    || !loadCell.isDataFlowing(RESUME_DATA_FLOW_MAX_AGE_MS)) {
                throw new IllegalStateException("the load cell is not delivering measurements,"
                        + " refusing to re-energize the motor without force feedback");
            }

            // Read HERE and nowhere later: clearStopLatch() nulls lastResult, so after the next line
            // there is no record anywhere of how this incident's motor stop actually went.
            SafeStopResult incidentStop = motorSafetyController.getLastStopResult();
            this.incidentStopTier = incidentStop == null ? null : incidentStop.tier().name();
            this.incidentStopVerified = incidentStop == null ? null : incidentStop.verified();

            motorSafetyController.clearStopLatch();
            current.reinitDriveForResume();

            machine.transition(TestState.RUNNING, "sensor recovered, drive re-initialised", actor);
            context.setSignalDispatchEnabled(true);
            current.loadCellThread.endHold();

            endGap("RESUMED");
        } catch (Throwable t) {
            logger.error("resume failed, stopping the motor", t);
            machine.transition(TestState.FAULT, "resume failed: " + t, actor);
            SafeStopResult result = motorSafetyController.safeStop("resume failed: " + t);
            if (result.needsOperatorAttention()) {
                logger.error("Resume failed AND the motor stop could not be verified: {}", result.detail());
            }
            endGap("RESUME_FAILED");
        }
    }

    /** The abort itself, on the run control thread: motor first, bookkeeping after. */
    private void performAbort(String reason, boolean duringIncident) {
        AbstractTest current = this.test;

        SafeStopResult result = motorSafetyController.safeStop(reason);
        if (result.needsOperatorAttention()) {
            logger.error("Abort requested but the motor stop could not be verified: {}", result.detail());
        }

        if (duringIncident) {
            endGap("ABORTED");
        }

        try {
            if (current != null) {
                current.abort(reason);
                // The runner thread is normally parked in processSignals(); signal 0 is exempt from
                // the dedup filter, so this reaches it even if a stop was already sent.
                TestContext context = current.getContext();
                if (context != null) {
                    context.sendSignal(0);
                }
            }
        } catch (Throwable t) {
            logger.error("aborting the test failed", t);
        }

        // An abort that leaves the runner parked forever would keep isRunning() true and refuse every
        // later run, so the wake-up above gets a bounded grace and then the interrupt.
        post(() -> {
            Thread runner = this.thread;
            if (running && runner != null && runner.isAlive()) {
                logger.warn("aborted run did not end within {} ms, interrupting the runner thread",
                        ABORT_INTERRUPT_GRACE_MS);
                runner.interrupt();
            }
        }, ABORT_INTERRUPT_GRACE_MS);
    }

    /**
     * Tracks what the broadcast snapshot reports, and owns the SAFE_HOLD timer: armed on entry,
     * cancelled on any transition out, so a resumed or aborted hold cannot be auto-aborted later.
     */
    private void onTransition(TestStateMachine.TransitionRecord record) {
        lastReason = record.reason();
        lastSequence = record.sequence();

        if (record.to() == TestState.SAFE_HOLD) {
            armSafeHoldTimer();
        } else {
            cancelSafeHoldTimer();
        }
    }

    /**
     * Server side and needing no UI, which is what makes "safety never depends on the browser" true.
     * It is bookkeeping rather than a safety action: the motor was stopped at t=0 by the loss path,
     * and {@code maxHoldForResumeMillis} is shorter than {@code safeHoldTimeoutMillis}, so Resume has
     * already been refused by the time this fires. What it prevents is a run sitting in INTERRUPTED
     * forever because the operator walked away.
     */
    private void armSafeHoldTimer() {
        RecoveryGates currentGates = this.gates;
        ScheduledExecutorService exec = this.runControl;
        if (currentGates == null || exec == null) {
            return;
        }

        long timeout = currentGates.safeHoldTimeoutMillis();
        synchronized (holdTimerLock) {
            if (safeHoldTimer != null) {
                return;
            }
            safeHoldDeadlineEpochMillis = System.currentTimeMillis() + timeout;
            try {
                safeHoldTimer = exec.schedule(() -> abortTest(SAFE_HOLD_TIMEOUT_ACTOR),
                        timeout, TimeUnit.MILLISECONDS);
            } catch (RejectedExecutionException e) {
                logger.warn("could not arm the safe hold timeout, the run control executor is gone", e);
            }
        }
    }

    private void cancelSafeHoldTimer() {
        synchronized (holdTimerLock) {
            if (safeHoldTimer != null) {
                safeHoldTimer.cancel(false);
                safeHoldTimer = null;
            }
            safeHoldDeadlineEpochMillis = null;
        }
    }

    /** The server's single Resume verdict; the UI renders it and must never compute its own. */
    private boolean canResumeNow() {
        AbstractTest current = this.test;
        return current != null && current.canResume();
    }

    /** Null rather than NaN: the wire format has no NaN, and "no reading yet" is the honest report. */
    private @Nullable Float lastKnownForce() {
        AbstractTest current = this.test;
        LoadCellThread cell = current == null ? null : current.loadCellThread;
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

    /**
     * Applies a terminal or lifecycle transition unless the run has already settled. Guarding on
     * isTerminal() is what lets an abort win over the FinishTestException that its own wake-up signal
     * produces moments later.
     */
    private void settle(TestState state, String reason) {
        TestStateMachine machine = this.stateMachine;
        if (machine != null && !machine.current().isTerminal()) {
            machine.transition(state, reason);
        }
    }

    /**
     * Closes the open gap with whatever the reconnector established, naming the stop of this gap's
     * own incident: {@link #performResume(String)} captures the {@link SafeStopResult} before
     * {@code clearStopLatch()} discards it, this method consumes that capture once, and
     * {@link #startThread(TestResult)} clears it.
     */
    void endGap(String outcome) {
        GapRecorder recorder = this.gapRecorder;
        if (recorder == null) {
            return;
        }

        SensorReconnector.Outcome recovery = this.lastRecoveryOutcome;
        LoadCellThread.GateResult gate = recovery == null ? null : recovery.gate();

        // An abort never passes through clearStopLatch(), so on that path the controller still holds
        // the incident's result and it can be read now. Only a resume has to have captured it earlier.
        String stopTier = this.incidentStopTier;
        Boolean stopVerified = this.incidentStopVerified;
        this.incidentStopTier = null;
        this.incidentStopVerified = null;
        if (stopTier == null) {
            SafeStopResult stop = motorSafetyController.getLastStopResult();
            if (stop != null) {
                stopTier = stop.tier().name();
                stopVerified = stop.verified();
            }
        }
        try {
            recorder.endGap(outcome,
                    gate == null ? null : gate.firstForce(),
                    gate == null ? null : gate.drift(),
                    gate == null ? null : gate.driftFraction(),
                    recovery == null ? 0 : recovery.attempts(),
                    stopTier, stopVerified);
        } catch (Throwable t) {
            // The sidecar is evidence, not control flow: a failed write must not take the run with it.
            logger.error("could not close the gap record", t);
        }
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

    /** 0 until the run holds a test with its devices: STARTING is recorded before the test exists. */
    private long droppedSampleCount() {
        AbstractTest current = this.test;
        if (current == null || current.deviceService == null) {
            return 0;
        }
        return current.deviceService.getLoadCell().getDroppedSampleCount();
    }

    private boolean post(Runnable work) {
        ScheduledExecutorService exec = this.runControl;
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

    private void post(Runnable work, long delayMillis) {
        ScheduledExecutorService exec = this.runControl;
        if (exec == null) {
            return;
        }
        try {
            exec.schedule(work, delayMillis, TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException ignored) {
            // The run is already being torn down, which is what the delayed check was guarding against.
        }
    }

    /**
     * Releases everything that belongs to one run, the run log included, so a refused start leaves
     * no open writer behind. The broadcaster is stopped explicitly because its incident ticker would
     * otherwise leak one repeating task per run onto an executor it does not own.
     */
    private void releaseRunScope() {
        cancelSafeHoldTimer();

        TestStateBroadcaster currentBroadcaster = this.broadcaster;
        if (currentBroadcaster != null) {
            currentBroadcaster.stop();
        }

        ScheduledExecutorService control = this.runControl;
        if (control != null) {
            control.shutdownNow();
        }

        // shutdown(), not shutdownNow(): the terminal transition's save is queued on it and is the
        // one that takes the run out of IN_PROGRESS, so dropping it would have the next boot sweep a
        // run that ended cleanly.
        ExecutorService persist = this.persistExecutor;
        if (persist != null) {
            persist.shutdown();
        }

        this.runControl = null;
        this.persistExecutor = null;
        this.broadcaster = null;

        // end() is idempotent, so stopThread() may close the same logger from the operator's thread.
        TestLogger log = this.testLogger;
        if (log != null) {
            log.end();
        }
        this.testLogger = null;
    }

    protected void retryShutdownOnException() {
        try {
            if (test != null) {
                test.destroy();
            }
        } catch (Exception e) {
            logger.error("Retrying destroy() failed, forcing a safe stop", e);
            test = null;
        }

        SafeStopResult result = motorSafetyController.safeStop("cleanup failed");
        if (result.coasting()) {
            // Already an emergency path, so still worth a line even though the drive answered.
            logger.warn("Emergency stop de-energized the drive, motor still coasting at {} rpm", result.motorSpeedRpm());
        } else if (result.needsOperatorAttention()) {
            logger.error("Emergency stop could not be verified: {}", result.detail());
        }
    }
}
