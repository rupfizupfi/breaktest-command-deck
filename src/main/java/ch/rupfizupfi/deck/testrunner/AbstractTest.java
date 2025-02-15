package ch.rupfizupfi.deck.testrunner;

import ch.rupfizupfi.deck.data.TestResult;
import ch.rupfizupfi.deck.device.DeviceService;
import ch.rupfizupfi.deck.testrunner.startup.check.AbstractCheck;
import ch.rupfizupfi.deck.testrunner.startup.check.CheckFailedException;
import ch.rupfizupfi.usbmodbus.Cfw11;

import java.util.ArrayList;
import java.util.List;

public abstract class AbstractTest implements SignalListener {
    protected LoadCellThread loadCellThread;
    protected Cfw11 cfw11;
    protected TestContext testContext;
    protected final TestResult testResult;
    protected final TestRunnerFactory testRunnerFactory;
    protected final TestLogger testLogger;
    protected DeviceService deviceService;
    protected long startTime;

    AbstractTest(TestResult testResult, TestLogger testLogger, TestRunnerFactory testRunnerFactory, DeviceService deviceService) {
        this.testResult = testResult;
        this.testLogger = testLogger;
        this.testRunnerFactory = testRunnerFactory;
        this.deviceService = deviceService;
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
        cfw11.setControlParameters(false, false, null, null, null);
        cfw11.setSpeedReferenceValueAsRpm(0);
        loadCellThread.setRunning(false);
    }

    void destroy() {
        deviceService.getFrequencyConverter().disconnect();
        cfw11 = null;
        loadCellThread = null;
        testContext = null;
        System.gc();
    }

    void log(String message) {
        testLogger.log(message);
    }

    protected void cfw11Pull() {
        cfw11.setDirection(false);
    }

    protected boolean cfw11IsPull() {
        return !cfw11.getDirection();
    }

    protected void cfw11Release() {
        cfw11.setDirection(true);
    }

    protected boolean cfw11IsRelease() {
        return cfw11.getDirection();
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
