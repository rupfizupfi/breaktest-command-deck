package ch.rupfizupfi.deck.testrunner;

import ch.rupfizupfi.deck.data.TestResult;
import ch.rupfizupfi.usbmodbus.Cfw11;
import org.springframework.messaging.simp.SimpMessagingTemplate;

public class DestructiveTest extends AbstractTest {
    DestructiveTest(TestResult testResult, SimpMessagingTemplate template) {
        super(testResult, template);
    }

    void setup() {
        testContext = new TestContext(testResult.getId(), testResult.testParameter.upperShutOffThreshold * 1000, testResult.testParameter.lowerShutOffThreshold * 1000);
        initContext();
        loadCellThread = new LoadCellThread(template, testContext);
        loadCellThread.setRunning(true);
        new Thread(loadCellThread).start();

        cfw11 = new Cfw11();
//        CommandChain commandChain = new CommandChain(1);
//        Cfw11Controller controller = new Cfw11Controller(cfw11, commandChain);
//        controller.start();

        log("upperShutOffThreshold " + testContext.getUpperLimit() + " Newton");
        log("lowerShutOffThreshold " + testContext.getLowerLimit() + " Newton");
        log("controller start");

        cfw11.setSpeedValueAsRpm((int) Math.round(testResult.testParameter.speed / 0.375));
        cfw11Pull();
        cfw11.setGeneralEnable(true);
        cfw11.setStart(true);

        template.convertAndSend("/topic/logs", "controller start cfw11");
    }

    @Override
    public void handleSignal(int signal) throws FinishTestException {
        finish();
    }
}
