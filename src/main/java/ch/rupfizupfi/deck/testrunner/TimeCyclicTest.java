package ch.rupfizupfi.deck.testrunner;

import ch.rupfizupfi.deck.data.TestResult;
import ch.rupfizupfi.deck.testrunner.cyclic.AnalyseData;
import ch.rupfizupfi.deck.testrunner.cyclic.CyclicTestContext;
import ch.rupfizupfi.usbmodbus.Cfw11;
import org.springframework.messaging.simp.SimpMessagingTemplate;

public class TimeCyclicTest extends CyclicTest {
    protected boolean analyseRun = true;
    protected AnalyseData[] analysedData = new AnalyseData[2];
    protected long releaseTime;
    protected long pullTime;

    TimeCyclicTest(TestResult testResult, SimpMessagingTemplate template) {
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

        cfw11 = new Cfw11();
        cfw11.setSpeedValueAsRpm((int) Math.round(30 / 0.375));
        cfw11.setDirection(true);
        cfw11.setGeneralEnable(true);
        cfw11.setStart(true);
    }

    @Override
    public void handleSignal(int signal) throws FinishTestException {
        if (this.analyseRun) {
            int index = signal - 1;
            int alter = index == 0 ? 1 : 0;
            analysedData[index].startTime = System.currentTimeMillis();
            analysedData[alter].endTime = System.currentTimeMillis();

            switch (signal) {
                case TestContext.RELEASE_SIGNAL:
                    log("Current min value " + loadCellThread.getMinValue());
                    cfw11Release();
                    log("change direction to release");
                    log("CycleCount " + testContext.getCycleCount());

                    analysedData[index].minForce = loadCellThread.getMinValue();
                    loadCellThread.setMinValue((float) targetUpperLimit);
                    break;
                case TestContext.PULL_SIGNAL:
                    log("Current max value " + loadCellThread.getMaxValue());
                    cfw11Pull();
                    log("change direction to pull");
                    log("CycleCount " + testContext.getCycleCount());

                    analysedData[index].maxForce = loadCellThread.getMaxValue();
                    loadCellThread.setMaxValue((float) targetLowerLimit);
                    break;
            }

            if (this.analyze()) {
                this.analyseRun = false;
                cfw11.setSpeedValueAsRpm((int) Math.round(testResult.testParameter.speed / 0.375));
            }
        } else {
            super.handleSignal(signal);
        }
    }

    protected boolean analyze() {
        for (AnalyseData analyseData : analysedData) {
            if (analyseData.startTime == 0 || analyseData.endTime == 0) {
                return false;
            }
        }

        this.releaseTime = analysedData[0].endTime - analysedData[0].startTime;
        this.pullTime = analysedData[1].endTime - analysedData[1].startTime;

        log("Release time: " + releaseTime + " ms");
        log("Pull time: " + pullTime + " ms");

        return true;
    }
}
