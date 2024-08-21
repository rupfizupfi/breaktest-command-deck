package ch.rupfizupfi.deck.testrunner;

import ch.rupfizupfi.deck.data.TestResult;
import ch.rupfizupfi.deck.testrunner.cyclic.AnalyseData;
import ch.rupfizupfi.deck.testrunner.cyclic.CyclicTestContext;
import ch.rupfizupfi.usbmodbus.Cfw11;
import org.springframework.messaging.simp.SimpMessagingTemplate;

public class TimeCyclicTest extends CyclicTest {
    protected final int analyseSpeed = 50;
    protected boolean analyseRun = true;
    protected AnalyseData[] analysedData = new AnalyseData[2];
    protected long releaseTime;
    protected long pullTime;

    TimeCyclicTest(TestResult testResult, SimpMessagingTemplate template) {
        super(testResult, template);
    }

    void setup() {
        this.analysedData[0] = new AnalyseData();
        this.analysedData[1] = new AnalyseData();

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
        cfw11.setSpeedValueAsRpm((int) Math.round(50 / 0.375));
        cfw11.setDirection(true);
        cfw11.setGeneralEnable(true);
        //cfw11.setSecondSpeedRampTime(0.3,0.3);
        cfw11.setUseSecondRamp(true);
        cfw11.setStart(true);
    }

    @Override
    public void handleSignal(int signal) throws FinishTestException {
        if (this.analyseRun) {
            int index = signal - 1;
            int alter = index == 0 ? 1 : 0;

            if (this.analyze()) {
                this.analyseRun = false;
                double startRampSeconds = testResult.testParameter.startRampSeconds;
                double stopRampSeconds = testResult.testParameter.stopRampSeconds;

                if (startRampSeconds > 0 && stopRampSeconds > 0) {
                    //cfw11.setSecondSpeedRampTime(startRampSeconds, stopRampSeconds);
                } else {
                    cfw11.setUseSecondRamp(false);
                }

                cfw11.setSpeedValueAsRpm((int) Math.round(testResult.testParameter.speed / 0.375));
            }

            switch (signal) {
                case TestContext.RELEASE_SIGNAL:
                    analysedData[index].startTime = System.currentTimeMillis();
                    analysedData[alter].endTime = System.currentTimeMillis();


                    log("Current min value " + loadCellThread.getMinValue());
                    cfw11Release();
                    log("change direction to release");
                    log("CycleCount " + testContext.getCycleCount());

                    analysedData[index].minForce = loadCellThread.getMinValue();
                    loadCellThread.setMinValue((float) targetUpperLimit);
                    break;
                case TestContext.PULL_SIGNAL:
                    if (targetLowerLimit - loadCellThread.getMinValue() > 300) {
                        analysedData[index].startTime = System.currentTimeMillis();
                        analysedData[alter].endTime = System.currentTimeMillis();
                    }

                    log("Current max value " + loadCellThread.getMaxValue());
                    cfw11Pull();
                    log("change direction to pull");
                    log("CycleCount " + testContext.getCycleCount());

                    analysedData[index].maxForce = loadCellThread.getMaxValue();
                    loadCellThread.setMaxValue((float) targetLowerLimit);
                    break;
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

        releaseTime = analysedData[0].endTime - analysedData[0].startTime;
        pullTime = analysedData[1].endTime - analysedData[1].startTime;

        log("Release time: " + releaseTime + " ms");
        log("Pull time: " + pullTime + " ms");

        releaseTime = releaseTime * analyseSpeed / this.testResult.testParameter.speed;
        pullTime = releaseTime * analyseSpeed / this.testResult.testParameter.speed;

        log("Adapted Release time: " + releaseTime + " ms");
        log("Adapted Pull time: " + pullTime + " ms");

        return true;
    }

    @Override
    void cleanup() {
        super.cleanup();
        cfw11.setUseSecondRamp(false);
    }
}
