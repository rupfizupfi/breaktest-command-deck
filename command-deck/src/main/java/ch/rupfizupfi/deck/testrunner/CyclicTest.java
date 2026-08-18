package ch.rupfizupfi.deck.testrunner;

import ch.rupfizupfi.deck.data.TestResult;
import ch.rupfizupfi.deck.device.DeviceService;
import ch.rupfizupfi.deck.testrunner.cyclic.CyclicTestContext;

public class CyclicTest extends AbstractTest {
    protected CyclicTestContext testContext;
    protected double targetLowerLimit;
    protected double targetUpperLimit;

    public CyclicTest(TestResult testResult, TestLogger testLogger, TestRunnerFactory testRunnerFactory, DeviceService deviceService, MotorSafetyController motorSafety) {
        super(testResult, testLogger, testRunnerFactory, deviceService, motorSafety);
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

        awaitLoadCellOrFail();
        log("load cell delivering measurements");

        connectFrequencyConverter();
        int speedRpm = (int) Math.round(testResult.testParameter.speed / 0.375);
        double startRampSeconds = testResult.testParameter.startRampSeconds;
        double stopRampSeconds = testResult.testParameter.stopRampSeconds;
        // one block so the whole energize sequence is atomic against the polling thread,
        // and energize() so a safe stop already requested by the load cell thread wins
        motorSafety.energize(drive -> {
            drive.setActionInCaseOfCommunicationError(2); // disable via general enable
            drive.setSpeedReferenceValueAsRpm(speedRpm);
            drive.setDirection(true);
            drive.setGeneralEnable(true);
            drive.setStart(true);

            if (startRampSeconds > 0 && stopRampSeconds > 0) {
                drive.setUseSecondRamp(true);
                drive.setSecondSpeedRampTime((int) (startRampSeconds * 10), (int) (stopRampSeconds * 10));
            }
        });
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
        try {
            motorSafety.withDrive(drive -> drive.setUseSecondRamp(false));
        } catch (RuntimeException e) {
            // the safe stop may have closed the handle; ramp state is cosmetic at this point
            log("could not reset second ramp: " + e.getMessage());
        }
    }
}
