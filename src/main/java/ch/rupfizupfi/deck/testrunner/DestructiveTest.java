package ch.rupfizupfi.deck.testrunner;

import ch.rupfizupfi.deck.data.Setting;
import ch.rupfizupfi.deck.data.TestResult;
import ch.rupfizupfi.deck.device.DeviceService;
import org.springframework.messaging.simp.SimpMessagingTemplate;

public class DestructiveTest extends AbstractTest {
    DestructiveTest(TestResult testResult, TestRunnerFactory testRunnerFactory, SimpMessagingTemplate template, DeviceService deviceService) {
        super(testResult, testRunnerFactory, template, deviceService);
    }

    void setup() {
        testContext = new TestContext(testResult.getId(), testResult.testParameter.upperShutOffThreshold * 1000, testResult.testParameter.lowerShutOffThreshold * 1000);
        initContext();
        loadCellThread = testRunnerFactory.createLoadCellThread(testContext, deviceService.getLoadCell());
        loadCellThread.start();

        log("upperShutOffThreshold " + testContext.getUpperLimit() + " Newton");
        log("lowerShutOffThreshold " + testContext.getLowerLimit() + " Newton");
        log("Destructive test start");

        deviceService.getFrequencyConverter().connect();
        cfw11 = deviceService.getFrequencyConverter().getHardwareComponent();
        cfw11.setActionInCaseOfCommunicationError(2); // disable via general enable
        cfw11.setSpeedReferenceValueAsRpm((int) Math.round(testResult.testParameter.speed / 0.375));
        cfw11Pull();
        cfw11.setGeneralEnable(true);
        cfw11.setStart(true);

        template.convertAndSend("/topic/logs", "controller start cfw11");
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
