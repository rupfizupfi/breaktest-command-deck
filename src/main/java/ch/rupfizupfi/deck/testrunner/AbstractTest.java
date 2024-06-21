package ch.rupfizupfi.deck.testrunner;

import ch.rupfizupfi.deck.data.TestResult;
import org.springframework.messaging.simp.SimpMessagingTemplate;

public abstract class AbstractTest implements SignalListener {
    protected final LoadCellThread loadCellThread;
    protected final CFW11 cfw11;
    protected boolean running = false;
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
        cfw11.setStart(false);
        cfw11.setSpeedValueAsRpm(0);
        loadCellThread.setRunning(false);
    }

    void log(String message) {
        template.convertAndSend("/topic/logs", message);
    }
}
