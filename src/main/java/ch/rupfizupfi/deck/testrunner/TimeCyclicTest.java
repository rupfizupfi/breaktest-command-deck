package ch.rupfizupfi.deck.testrunner;

import ch.rupfizupfi.deck.data.TestResult;
import ch.rupfizupfi.deck.testrunner.cyclic.AnalyseData;
import ch.rupfizupfi.deck.testrunner.cyclic.CyclicTestContext;
import ch.rupfizupfi.deck.testrunner.cyclic.TimeProcessor;
import ch.rupfizupfi.usbmodbus.Cfw11;
import org.springframework.messaging.simp.SimpMessagingTemplate;

public class TimeCyclicTest extends CyclicTest {
    private static final int ANALYSE_SPEED = 50;
    private static final int SPEED_DIVISOR = 375;
    private static final int RAMP_TIME_MULTIPLIER = 10;
    private static final int FORCE_THRESHOLD = 300;
    private static final int INITIAL_SPEED = 50;
    private static final int INITIAL_RAMP_TIME = 3;

    private boolean analyseRun = true;
    private final AnalyseData[] analysedData = new AnalyseData[2];
    private TimeProcessor timeProcessor;

    public TimeCyclicTest(TestResult testResult, SimpMessagingTemplate template) {
        super(testResult, template);
    }

    public void setup() {
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
        log("time cyclic test start");

        cfw11 = new Cfw11();
        cfw11.setActionInCaseOfCommunicationError(2); // disable via general enable
        cfw11.setSpeedValueAsRpm((int) Math.round(INITIAL_SPEED / (double) SPEED_DIVISOR));
        cfw11.setSecondSpeedRampTime(INITIAL_RAMP_TIME, INITIAL_RAMP_TIME); // 300ms each
        cfw11.setControlParameters(true, true, true, null, true);
    }

    @Override
    public void handleSignal(int signal) throws FinishTestException {
        if (this.analyseRun && this.analyze()) {
            this.analyseRun = false;
            double startRampSeconds = testResult.testParameter.startRampSeconds;
            double stopRampSeconds = testResult.testParameter.stopRampSeconds;

            if (startRampSeconds > 0 && stopRampSeconds > 0) {
                cfw11.setSecondSpeedRampTime((int) (startRampSeconds * RAMP_TIME_MULTIPLIER), (int) (stopRampSeconds * RAMP_TIME_MULTIPLIER));
            } else {
                cfw11.setUseSecondRamp(false);
            }

            cfw11.setSpeedValueAsRpm((int) Math.round(testResult.testParameter.speed / (double) SPEED_DIVISOR));
        }

        if (this.analyseRun) {
            handleAnalyseRun(signal);
        } else {
            super.handleSignal(signal);
        }
    }

    private void handleAnalyseRun(int signal) {
        int index = signal - 1;
        int alter = index == 0 ? 1 : 0;

        switch (signal) {
            case TestContext.RELEASE_SIGNAL:
                handleReleaseSignal(index, alter);
                break;
            case TestContext.PULL_SIGNAL:
                handlePullSignal(index, alter);
                break;
        }
    }

    private void handleReleaseSignal(int index, int alter) {
        analysedData[index].setAndCheckStartTime(System.currentTimeMillis());
        analysedData[alter].endTime = System.currentTimeMillis();
        float minForceValue = loadCellThread.getMinValue();

        log("Current min value " + minForceValue);
        log("<b>init: start release round<b/>");
        cfw11Release();

        if (Math.abs(minForceValue - targetLowerLimit) < FORCE_THRESHOLD) {
            analysedData[index].minForce = minForceValue;
        }

        loadCellThread.setMinValue((float) targetUpperLimit);
    }

    private void handlePullSignal(int index, int alter) {
        if (targetLowerLimit - loadCellThread.getMinValue() > FORCE_THRESHOLD) {
            analysedData[index].setAndCheckStartTime(System.currentTimeMillis());
            analysedData[alter].endTime = System.currentTimeMillis();
        }

        float maxForceValue = loadCellThread.getMaxValue();

        log("Current max value " + maxForceValue);
        log("<b>init: start pull round<b/>");
        cfw11Pull();

        if (Math.abs(maxForceValue - targetUpperLimit) < FORCE_THRESHOLD) {
            analysedData[index].maxForce = maxForceValue;
        }

        loadCellThread.setMaxValue((float) targetLowerLimit);
    }

    /**
     * Analyze the data and calculate the release and pull time
     * @return true if the data is complete
     */
    protected boolean analyze() {
        for (AnalyseData analyseData : analysedData) {
            if (analyseData.startTime == 0 || analyseData.endTime == 0) {
                return false;
            }
        }

        long releaseTime = analysedData[0].endTime - analysedData[0].startTime;
        long pullTime = analysedData[1].endTime - analysedData[1].startTime;

        log("Release time: " + releaseTime + " ms");
        log("Pull time: " + pullTime + " ms");

        releaseTime = releaseTime * ANALYSE_SPEED / this.testResult.testParameter.speed;
        pullTime = pullTime * ANALYSE_SPEED / this.testResult.testParameter.speed;

        log("Adapted Release time: " + releaseTime + " ms");
        log("Adapted Pull time: " + pullTime + " ms");

        timeProcessor = new TimeProcessor(testContext, releaseTime, pullTime);
        return true;
    }

    @Override
    public void cleanup() {
        super.cleanup();
        if (timeProcessor != null) {
            timeProcessor.stop();
        }
    }
}