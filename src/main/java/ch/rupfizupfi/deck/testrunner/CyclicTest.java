package ch.rupfizupfi.deck.testrunner;

import ch.rupfizupfi.deck.data.TestResult;
import ch.rupfizupfi.deck.device.DeviceService;
import ch.rupfizupfi.deck.testrunner.cyclic.CyclicTestContext;

public class CyclicTest extends AbstractTest {
    protected CyclicTestContext testContext;
    protected double targetLowerLimit;
    protected double targetUpperLimit;

    public CyclicTest(TestResult testResult, TestLogger testLogger, TestRunnerFactory testRunnerFactory, DeviceService deviceService) {
        super(testResult, testLogger, testRunnerFactory, deviceService);
    }

    void setup() {
        testContext = new CyclicTestContext(testResult.getId(), testResult.testParameter.upperTurnForce * 1000, testResult.testParameter.lowerTurnForce * 1000, testResult.testParameter.cycleCount);
        initContext();
        targetLowerLimit = testContext.getLowerLimit();
        targetUpperLimit = testContext.getUpperLimit();

        loadCellThread = testRunnerFactory.createLoadCellThread(testContext, deviceService.getLoadCell());
        loadCellThread.start();

        log("upperShutOffThreshold " + testContext.getUpperLimit() + " Newton");
        log("lowerShutOffThreshold " + testContext.getLowerLimit() + " Newton");
        log("CycleCount " + testContext.getCycleCount());
        log("cyclic test start");

        deviceService.getFrequencyConverter().connect();
        cfw11 = deviceService.getFrequencyConverter().getHardwareComponent();
        cfw11.setActionInCaseOfCommunicationError(2); // disable via general enable
        cfw11.setSpeedReferenceValueAsRpm((int) Math.round(testResult.testParameter.speed / 0.375));
        cfw11.setDirection(true);
        cfw11.setGeneralEnable(true);
        cfw11.setStart(true);

        double startRampSeconds = testResult.testParameter.startRampSeconds;
        double stopRampSeconds = testResult.testParameter.stopRampSeconds;
        if (startRampSeconds > 0 && stopRampSeconds > 0) {
            cfw11.setUseSecondRamp(true);
            cfw11.setSecondSpeedRampTime((int) (startRampSeconds * 10), (int) (stopRampSeconds * 10));
        }
    }

    void initContext() {
        super.testContext = testContext;
        super.initContext();
    }

    @Override
    public void handleSignal(int signal) throws FinishTestException {
        switch (signal) {
            case 0:
                finish();
                break;
            case TestContext.RELEASE_SIGNAL: //upper limit triggered
                if (cfw11IsPull()) {
                    log("Current min value " + loadCellThread.getMinValue());
                    double diff = targetLowerLimit - loadCellThread.getMinValue();

                    if (diff != 0.0) {
                        testContext.setLowerLimit(Math.max(testContext.getLowerLimit() + diff, targetLowerLimit));
                        log("New lower limit " + testContext.getLowerLimit());
                    }

                    log("change direction to forward");
                    log("CycleCount " + testContext.getCycleCount());

                    cfw11Release();
                    loadCellThread.setMinValue((float) targetUpperLimit);
                }
                break;
            case TestContext.PULL_SIGNAL:
                if (cfw11IsRelease()) {
                    log("Current max value " + loadCellThread.getMaxValue());
                    double diff = targetUpperLimit - loadCellThread.getMaxValue();

                    if (diff != 0.0) {
                        testContext.setUpperLimit(Math.min(testContext.getUpperLimit() + diff, targetUpperLimit));
                        log("New upper limit " + testContext.getUpperLimit());
                    }

                    log("change direction to backword");
                    log("CycleCount " + testContext.getCycleCount());

                    cfw11Pull();
                    loadCellThread.setMaxValue((float) targetLowerLimit);
                    testContext.decrementCycleCount();
                }
                break;
        }
    }

    @Override
    void cleanup() {
        super.cleanup();
        cfw11.setUseSecondRamp(false);
    }
}
