package ch.rupfizupfi.deck.testrunner;

import ch.rupfizupfi.deck.data.TestResult;
import ch.rupfizupfi.deck.device.DeviceService;
import ch.rupfizupfi.deck.device.api.Drive;
import ch.rupfizupfi.deck.testrunner.startup.check.AbstractCheck;
import ch.rupfizupfi.deck.testrunner.startup.check.CheckFailedException;

import java.util.ArrayList;
import java.util.List;

public abstract class AbstractTest implements SignalListener {
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

    private boolean frequencyConverterConnected = false;

    AbstractTest(TestResult testResult, TestLogger testLogger, TestRunnerFactory testRunnerFactory, DeviceService deviceService, MotorSafetyController motorSafety) {
        this.testResult = testResult;
        this.testLogger = testLogger;
        this.testRunnerFactory = testRunnerFactory;
        this.deviceService = deviceService;
        this.motorSafety = motorSafety;
        this.startTime = System.currentTimeMillis();
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
            loadCellThread.setRunning(false);
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

    protected void connectFrequencyConverter() {
        deviceService.getFrequencyConverter().connect();
        frequencyConverterConnected = true;
    }

    void destroy() {
        // only balance a connect we actually made, otherwise a setup() that threw early
        // drives the shared reference count negative
        if (frequencyConverterConnected) {
            frequencyConverterConnected = false;
            deviceService.getFrequencyConverter().disconnect();
        }
        loadCellThread = null;
        testContext = null;
        System.gc();
    }

    void log(String message) {
        testLogger.log(message);
    }

    protected void cfw11Pull() {
        motorSafety.withDrive(drive -> drive.setDirection(false));
    }

    protected boolean cfw11IsPull() {
        return !motorSafety.queryDrive(Drive::getDirection);
    }

    protected void cfw11Release() {
        motorSafety.withDrive(drive -> drive.setDirection(true));
    }

    protected boolean cfw11IsRelease() {
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
