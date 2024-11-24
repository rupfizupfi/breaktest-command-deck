package ch.rupfizupfi.deck.testrunner;

import ch.rupfizupfi.deck.data.Setting;
import ch.rupfizupfi.deck.data.TestResult;
import ch.rupfizupfi.deck.device.DeviceService;
import ch.rupfizupfi.usbmodbus.Cfw11;
import org.springframework.messaging.simp.SimpMessagingTemplate;

public abstract class AbstractTest implements SignalListener {
    protected LoadCellThread loadCellThread;
    protected Cfw11 cfw11;
    protected TestContext testContext;
    protected final TestResult testResult;
    protected final SimpMessagingTemplate template;
    protected final TestRunnerFactory testRunnerFactory;
    protected DeviceService deviceService;
    protected long startTime;

    AbstractTest(TestResult testResult, TestRunnerFactory testRunnerFactory, SimpMessagingTemplate template, DeviceService deviceService) {
        this.testResult = testResult;
        this.template = template;
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
        template.convertAndSend("/topic/logs", message);
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
}
