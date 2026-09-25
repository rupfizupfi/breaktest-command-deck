package ch.rupfizupfi.deck.testrunner;

import ch.rupfizupfi.deck.data.TestResult;
import ch.rupfizupfi.deck.device.DeviceService;
import ch.rupfizupfi.deck.device.api.Drive;
import ch.rupfizupfi.deck.filesystem.CSVStoreService;
import ch.rupfizupfi.deck.testrunner.startup.check.AbstractCheck;
import ch.rupfizupfi.deck.testrunner.startup.check.CheckFailedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

public abstract class AbstractTest implements SignalListener, SensorLossListener {
    private static final Logger logger = LoggerFactory.getLogger(AbstractTest.class);

    /** Motor energization must never be gated on anything weaker than a real, fresh sample. */
    private static final long LOAD_CELL_STARTUP_TIMEOUT_MS = 2000;

    protected LoadCellThread loadCellThread;
    protected TestContext testContext;
    protected final TestResult testResult;
    protected final TestRunnerFactory testRunnerFactory;
    protected final TestLogger testLogger;
    protected final MotorSafetyController motorSafety;
    protected DeviceService deviceService;
    protected long startTime;

    protected TestRunnerThread runner;
    protected TestStateMachine stateMachine;
    protected SensorReconnector reconnector;
    protected GapRecorder gapRecorder;
    protected RecoveryGates gates;

    /** Set by {@link #abort(String)} from an operator thread, read on the runner thread in finish(). */
    protected volatile boolean aborted = false;

    /**
     * Losses this run has already survived. Incremented only on the measurement thread, but read by
     * {@link #canResume()} on an operator thread, hence volatile.
     */
    protected volatile int lossCount = 0;

    /** Whether the last completed recovery gate passed. A new loss invalidates the previous verdict. */
    private volatile boolean lastGatePassed = false;

    /** When the current SAFE_HOLD began; 0 while no hold is standing. */
    private volatile long holdEnteredAtMillis = 0;

    /** Created once and shared by the CSV writer and the gap recorder, which must agree on the run. */
    private CSVStoreService.TestRunFiles runFiles;

    private boolean frequencyInverterConnected = false;

    AbstractTest(TestResult testResult, TestLogger testLogger, TestRunnerFactory testRunnerFactory, DeviceService deviceService, MotorSafetyController motorSafety) {
        this.testResult = testResult;
        this.testLogger = testLogger;
        this.testRunnerFactory = testRunnerFactory;
        this.deviceService = deviceService;
        this.motorSafety = motorSafety;
        this.startTime = System.currentTimeMillis();
    }

    /**
     * Hands the run its per-run recovery collaborators. Deliberately a setter and not four more
     * constructor parameters: {@code TestRunnerFactory#createTestRunner} resolves constructor
     * parameters by TYPE out of the ApplicationContext, so anything added there would have to be a
     * singleton bean - and a state machine or a reconnector shared between runs carries one run's
     * history and listeners into the next.
     * <p>
     * Must be called before {@link #setup()}; see {@link #requireRecoveryWiring()}.
     */
    public void initRecovery(TestRunnerThread runner, TestStateMachine stateMachine,
                             GapRecorder gapRecorder, CSVStoreService.TestRunFiles runFiles,
                             RecoveryGates gates) {
        this.runner = runner;
        this.stateMachine = stateMachine;
        this.gapRecorder = gapRecorder;
        this.runFiles = runFiles;
        this.gates = gates;
    }

    /**
     * Separate from {@link #initRecovery} because {@code SensorReconnector} is constructed around
     * the run's {@code LoadCellThread}, which only exists once {@link #setup()} has run - while the
     * state machine has to be in place before that, or a setup() failure has nowhere to be recorded.
     * A run whose reconnector never arrives holds and aborts; it never resumes.
     */
    public void setReconnector(SensorReconnector reconnector) {
        this.reconnector = reconnector;
    }

    /**
     * The run's file set. Lazily created and cached, because the measurement CSV and the gap record
     * must describe the same run - two calls to {@code createRunFiles} would be two file sets.
     */
    public CSVStoreService.TestRunFiles runFiles() {
        if (runFiles == null) {
            // Only reachable for a run that was never wired through initRecovery; the runner owns the
            // file set because it has to build the gap recorder around it before the test exists.
            runFiles = testRunnerFactory.createRunFiles(testResult.getId());
        }
        return runFiles;
    }

    /**
     * Refuses to set up a run whose loss path is not wired: without a state machine
     * {@link #onSensorLoss} cannot even record the incident, and the operator would get a motor that
     * stopped for no stated reason.
     */
    protected void requireRecoveryWiring() {
        if (stateMachine == null || gates == null) {
            throw new IllegalStateException(
                    "initRecovery(...) was not called before setup(), refusing to start a run whose"
                            + " sensor-loss path is not wired");
        }
    }

    abstract void setup();

    void initContext() {
        testContext.addSignalListener(this);
    }

    TestContext getContext() {
        return testContext;
    }

    void finish() throws FinishTestException {
        cleanup();
        String className = this.getClass().getSimpleName();
        log(className + " finishing test");

        throw new FinishTestException();
    }

    /**
     * Runs on the MEASUREMENT thread, which has already de-energized the drive in
     * {@code LoadCellThread#sensorLost} before calling this. Everything here is bookkeeping and
     * handover - the motor is not waiting on it, but the measurement thread is, so nothing may
     * block and nothing may throw back into the watchdog.
     */
    @Override
    public void onSensorLoss(String reason, float lastKnownForce) {
        try {
            lossCount++;
            lastGatePassed = false;
            // Before the transition, so the SENSOR_LOST broadcast already reports this incident's
            // own attempt count rather than the previous incident's.
            if (runner != null) {
                runner.beginIncident();
            }
            stateMachine.transition(TestState.SENSOR_LOST, reason);

            TestContext context = testContext;
            if (context != null) {
                // Gate first, then drain: a crossing that arrives between the two would otherwise be
                // queued after the drain and dispatched into a drive that is being re-initialised.
                context.setSignalDispatchEnabled(false);
                context.drainSignals();
            }

            onHoldEntry();

            holdEnteredAtMillis = System.currentTimeMillis();
            stateMachine.transition(TestState.SAFE_HOLD, reason);

            startRecovery(reason, lastKnownForce);
        } catch (Throwable t) {
            logger.error("sensor-loss handling failed, the run cannot be held safely", t);
            log("error while entering the safe hold: " + t);
            if (stateMachine != null) {
                stateMachine.transition(TestState.FAULT, "sensor-loss handling failed: " + t);
            }
        }
    }

    /**
     * The measurement loop died rather than the sensor. Nothing to reconnect to, so there is no hold:
     * record the run as a FAULT and unwind it. The motor is already stopped by the caller.
     */
    @Override
    public void onWatchdogFailure(String detail) {
        log("load cell thread failed: " + detail);
        if (stateMachine != null) {
            stateMachine.transition(TestState.FAULT, detail);
        }
        abort(detail);
    }

    /**
     * Type-specific work at the start of a hold. Runs on the measurement thread, so an override must
     * be short and must not wait on the drive.
     */
    protected void onHoldEntry() {
    }

    /**
     * Hands the loss to the reconnector on a thread of its own. The measurement thread must not wait
     * for a reconnect: it stays responsible for parking the hold and for answering
     * {@code LoadCellThread#endHold()}, and a reconnect window is seconds long.
     */
    private void startRecovery(String reason, float lastKnownForce) {
        if (reconnector == null) {
            log("no reconnector wired for this run, the hold can only end in an abort");
            return;
        }

        TestContext context = testContext;
        double configured = context == null ? 0
                : Math.max(Math.abs(context.getUpperLimit()), Math.abs(context.getLowerLimit()));
        // Floored here, once; the gate treats the envelope as final.
        double envelope = Math.max(configured, gates.minEnvelopeNewton());

        Thread worker = new Thread(() -> {
            try {
                SensorReconnector.Outcome outcome =
                        reconnector.attemptRecovery(reason, lastKnownForce, envelope);
                lastGatePassed = outcome.recovered() && outcome.gate() != null && outcome.gate().passed();
                log("sensor recovery after " + outcome.attempts() + " attempt(s): " + outcome.detail());
                // The verdict is produced here, on the loss path, but it is resumeTest() and the
                // incident broadcast that need it - so it is handed back rather than kept local.
                if (runner != null) {
                    runner.recordRecoveryOutcome(outcome);
                }
            } catch (Throwable t) {
                // A failed recovery is an abort, never a resume - and it must not take a thread down
                // silently, because the operator is looking at a banner that is waiting for it.
                lastGatePassed = false;
                logger.error("sensor recovery failed", t);
                log("sensor recovery failed: " + t);
            }
        }, "sensor-recovery-" + testResult.getId());
        worker.setDaemon(true);
        worker.start();
    }

    /**
     * Ends the run from outside the runner thread - the operator's Abort button, or the SAFE_HOLD
     * timeout. Deliberately does not touch the hardware: signal 0 unblocks the runner from
     * {@code processSignals()} and lets it unwind through the normal {@link #finish()} path, so an
     * abort tears the run down exactly the way a stop does.
     */
    public void abort(String reason) {
        aborted = true;
        log("abort: " + reason);

        TestContext context = testContext;
        if (context == null) {
            return;
        }
        // Drain before re-enabling, so re-opening the gate cannot let a pre-loss crossing out with
        // the stop. Signal 0 passes the gate either way; enabling makes that independent of how the
        // run reached here.
        context.drainSignals();
        context.setSignalDispatchEnabled(true);
        context.sendSignal(0);
    }

    /** Whether a sensor-loss hold of this test type may end in a resume rather than an abort. */
    protected boolean supportsResume() {
        return false;
    }

    /** Re-energizes the drive for a resumed run, as {@link #setup()} does for a fresh one. */
    protected void reinitDriveForResume() {
        throw new UnsupportedOperationException(
                getClass().getSimpleName() + " does not support resuming after a sensor loss");
    }

    /**
     * Whether the standing hold may end in a resume. Evaluated under the state lock by
     * {@code TestStateMachine#compareAndTransition}'s guard, so it stays cheap and side-effect free.
     */
    public boolean canResume() {
        if (gates == null || !gates.resumeEnabled() || !supportsResume()) {
            return false;
        }
        if (!lastGatePassed) {
            return false;
        }
        if (lossCount > gates.maxLossesPerRun()) {
            return false;
        }
        // holdEnteredAtMillis is 0 when no hold ever started, which makes this comfortably false.
        if (System.currentTimeMillis() - holdEnteredAtMillis >= gates.maxHoldForResumeMillis()) {
            return false;
        }

        // The gate that decides resumable from abort-only: past tier 1 the frequency inverter is deliberately left
        // handle-less with its refcount already dropped, so there is nothing to re-energize through -
        // note frequencyInverterConnected still reads true there. See the resume gates in
        // doc/06-feature-work/testrunner-safety/loadcell-recovery-design.md.
        return motorSafety.isDriveAvailable();
    }

    // The resume sequence lives ONLY in TestRunnerThread#performResume - call the runner, never
    // re-derive it here. A second copy is a second ordering of a safety sequence.

    /**
     * This method can be executed twice!!
     */
    void cleanup() {
        SafeStopResult result = motorSafety.safeStop("test cleanup");
        // A confirmed standstill is the expected ending and stays out of the operator log.
        if (result.coasting()) {
            // De-energized and the drive still answering: on a loaded rig the mass simply
            // runs out its own inertia. Informational, not a fault.
            log("motor de-energized, coasting down at " + result.motorSpeedRpm() + " rpm");
        } else if (result.needsOperatorAttention()) {
            // Only for a motor this run actually energized. cleanup() also runs after a failed
            // startup check, where nothing was ever switched on and no drive answers - sending the
            // operator to the E-stop for that would devalue the message for the case that matters.
            log("WARNING: motor stop could not be confirmed (" + result.detail() + ") - use the physical E-stop");
        }
        if (loadCellThread != null) {
            // stop(), not just setRunning(false): a measurement thread parked in a recovery hold is
            // not looking at that flag and would outlive destroy() still holding the run's CSV.
            // Idempotent, which this method needs it to be.
            loadCellThread.stop();
        }
        if (reconnector != null) {
            // Run teardown is the reconnector's documented end: a reconnect still in flight is
            // chasing a sensor for a run that is over, and its thread would outlive it.
            reconnector.shutdownNow();
        }
    }

    /**
     * Refuses to continue unless the load cell is actually delivering measurements.
     * The device connects asynchronously and the USB driver opens the port on its own thread, so
     * a returned connect() is not evidence of a live sensor - only a fresh sample is.
     */
    protected void awaitLoadCellOrFail() {
        if (!deviceService.getLoadCell().awaitFreshMeasurement(LOAD_CELL_STARTUP_TIMEOUT_MS)) {
            throw new IllegalStateException(
                    "no load cell measurement within " + LOAD_CELL_STARTUP_TIMEOUT_MS
                            + " ms - refusing to energize the motor without force feedback");
        }
    }

    protected void connectFrequencyInverter() {
        deviceService.getFrequencyInverter().connect();
        frequencyInverterConnected = true;
    }

    void destroy() {
        // only balance a connect we actually made, otherwise a setup() that threw early
        // drives the shared reference count negative
        if (frequencyInverterConnected) {
            frequencyInverterConnected = false;
            deviceService.getFrequencyInverter().disconnect();
        }
        loadCellThread = null;
        testContext = null;
    }

    void log(String message) {
        testLogger.log(message);
    }

    protected void drivePull() {
        motorSafety.withDrive(drive -> drive.setDirection(false));
    }

    protected boolean driveIsPull() {
        return !motorSafety.queryDrive(Drive::getDirection);
    }

    protected void driveRelease() {
        motorSafety.withDrive(drive -> drive.setDirection(true));
    }

    protected boolean driveIsRelease() {
        return motorSafety.queryDrive(Drive::getDirection);
    }

    public void runStartupChecks() throws CheckFailedException {
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
}
