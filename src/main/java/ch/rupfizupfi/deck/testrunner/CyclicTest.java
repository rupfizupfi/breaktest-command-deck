package ch.rupfizupfi.deck.testrunner;

import ch.rupfizupfi.deck.data.TestResult;
import ch.rupfizupfi.deck.testrunner.cyclic.CyclicTestContext;
import ch.rupfizupfi.usbmodbus.Cfw11;
import org.springframework.messaging.simp.SimpMessagingTemplate;

public class CyclicTest extends AbstractTest {
    protected CyclicTestContext testContext;
    protected double targetLowerLimit;
    protected double targetUpperLimit;

    CyclicTest(TestResult testResult, SimpMessagingTemplate template) {
        super(testResult, template);
    }

    void setup() {
        testContext = new CyclicTestContext(testResult.getId(), testResult.testParameter.upperTurnForce * 1000, testResult.testParameter.lowerTurnForce * 1000, testResult.testParameter.cycleCount);
        initContext();
        targetLowerLimit = testContext.getLowerLimit();
        targetUpperLimit = testContext.getUpperLimit();

        loadCellThread = new LoadCellThread(template, testContext);
        loadCellThread.setRunning(true);
        new Thread(loadCellThread).start();

        log("upperShutOffThreshold " + testContext.getUpperLimit() + " Newton");
        log("lowerShutOffThreshold " + testContext.getLowerLimit() + " Newton");
        log("CycleCount " + testContext.getCycleCount());
        log("cyclic test start");

        Cfw11 cfw11 = new Cfw11();
        cfw11.setSpeedValueAsRpm((int) Math.round(testResult.testParameter.speed / 0.375));
        cfw11.setDirection(false);
        cfw11.setGeneralEnable(true);
        cfw11.setStart(true);

//        controller.setSpeedValueAsRpm((int) Math.round(testResult.testParameter.speed / 0.375));
//        controller.setStart(true);
    }

    @Override
    public void handleSignal(int signal) throws FinishTestException {
        switch (signal) {
            case 0:
                finish();
                break;
            case 1: //upper limit triggered
                if (!cfw11.getDirection()) {
                    double diff = targetLowerLimit - loadCellThread.getMinValue();
                    if (diff != 0.0) {
                        log("Current min value " + loadCellThread.getMinValue());
                        testContext.setLowerLimit(testContext.getLowerLimit() + diff);
                        log("New lower limit " + testContext.getLowerLimit());
                    }

                    log("change direction to forward");
                    log("CycleCount " + testContext.getCycleCount());

                    cfw11.setDirection(true);
                    loadCellThread.setMinValue((float) targetUpperLimit);
                }
                break;
            case 2:
                if (cfw11.getDirection()) {
                    double diff = targetUpperLimit - loadCellThread.getMaxValue();
                    if (diff != 0.0) {
                        log("Current max value " + loadCellThread.getMaxValue());
                        testContext.setUpperLimit(testContext.getUpperLimit() + diff);
                        log("New upper limit " + testContext.getUpperLimit());
                    }

                    log("change direction to backword");
                    log("CycleCount " + testContext.getCycleCount());

                    cfw11.setDirection(false);
                    loadCellThread.setMaxValue((float) targetLowerLimit);
                    testContext.decrementCycleCount();
                }
                break;

        }
    }
}
