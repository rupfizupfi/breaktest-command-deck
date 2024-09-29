package ch.rupfizupfi.deck.testrunner;

import ch.rupfizupfi.deck.data.TestResult;
import ch.rupfizupfi.usbmodbus.Cfw11;
import org.springframework.messaging.simp.SimpMessagingTemplate;

public abstract class AbstractTest implements SignalListener {
    protected LoadCellThread loadCellThread;
    protected Cfw11 cfw11;
    protected TestResult testResult;
    protected TestContext testContext;
    protected final SimpMessagingTemplate template;

    AbstractTest(TestResult testResult, SimpMessagingTemplate template) {
        this.testResult = testResult;
        this.template = template;
    }

    abstract void setup();

    void initContext(){
        testContext.addSignalListener(this);
    }

    TestContext getContext(){
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
        cfw11.setSpeedValueAsRpm(0);
        loadCellThread.setRunning(false);
    }

    void destroy() {
        cfw11.getUsbComm().closeUSBComm();
        cfw11 = null;
        loadCellThread = null;
        testContext = null;
        System.gc();
    }

    void log(String message) {
        template.convertAndSend("/topic/logs", message);
    }

    protected void cfw11Pull(){
        cfw11.setDirection(false);
    }

    protected boolean cfw11IsPull(){
        return !cfw11.getDirection();
    }

    protected void cfw11Release(){
        cfw11.setDirection(true);
    }

    protected boolean cfw11IsRelease() {
        return cfw11.getDirection();
    }
}
