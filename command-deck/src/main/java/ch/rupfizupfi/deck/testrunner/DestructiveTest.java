package ch.rupfizupfi.deck.testrunner;

import ch.rupfizupfi.deck.data.Setting;
import ch.rupfizupfi.deck.data.TestResult;
import ch.rupfizupfi.deck.device.DeviceService;

public class DestructiveTest extends AbstractTest {
    public DestructiveTest(TestResult testResult, TestLogger testLogger, TestRunnerFactory testRunnerFactory, DeviceService deviceService, MotorSafetyController motorSafety) {
        super(testResult, testLogger, testRunnerFactory, deviceService, motorSafety);
    }

    void setup() {
        testContext = new TestContext(testResult.getId(), testResult.testParameter.upperShutOffThreshold * 1000, testResult.testParameter.lowerShutOffThreshold * 1000);
        initContext();
        loadCellThread = testRunnerFactory.createLoadCellThread(testContext, deviceService.getLoadCell());
        loadCellThread.start();

        log("upperShutOffThreshold " + testContext.getUpperLimit() + " Newton");
        log("lowerShutOffThreshold " + testContext.getLowerLimit() + " Newton");
        log("Destructive test start");

        awaitLoadCellOrFail();
        log("load cell delivering measurements");

        connectFrequencyConverter();
        int speedRpm = (int) Math.round(testResult.testParameter.speed / 0.375);
        // one block so the whole energize sequence is atomic against the polling thread,
        // and energize() so a safe stop already requested by the load cell thread wins
        motorSafety.energize(drive -> {
            drive.setActionInCaseOfCommunicationError(2); // disable via general enable
            drive.setSpeedReferenceValueAsRpm(speedRpm);
            drive.setDirection(false); // pull
            // a previous cyclic run may have left the second ramp enabled in the drive
            drive.setUseSecondRamp(false);
            drive.setGeneralEnable(true);
            drive.setStart(true);
        });
    }

    @Override
    public void handleSignal(int signal) throws FinishTestException {
        finish();
    }

    @Override
    void finish() throws FinishTestException {
        if (System.currentTimeMillis() - startTime > 2000) {
            var settingsRepository = this.deviceService.getSettingRepository();
            try {
                if (settingsRepository.getSettingValue(Setting.Key.TESTRUNNER_SUCK)) {
                    new SuckJob(settingsRepository.getSettingValue(Setting.Key.TESTRUNNER_SUCK_DURATION)).start();
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        super.finish();
    }
}
