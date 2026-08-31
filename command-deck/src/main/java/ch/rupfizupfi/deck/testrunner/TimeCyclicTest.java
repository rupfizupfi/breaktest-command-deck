package ch.rupfizupfi.deck.testrunner;

import ch.rupfizupfi.deck.data.TestResult;
import ch.rupfizupfi.deck.device.DeviceService;
import ch.rupfizupfi.deck.testrunner.cyclic.AnalyseData;
import ch.rupfizupfi.deck.testrunner.cyclic.CyclicTestContext;
import ch.rupfizupfi.deck.testrunner.cyclic.TimeProcessor;

public class TimeCyclicTest extends CyclicTest {
    private static final int ANALYSE_SPEED = 50;
    private static final int SPEED_DIVISOR = 375;
    private static final int RAMP_TIME_MULTIPLIER = 10;
    private static final int FORCE_THRESHOLD = 300;
    private static final int INITIAL_SPEED = 50;
    private static final int INITIAL_RAMP_TIME = 3;

    /**
     * Volatile because a resume re-arms the analyse phase from the operator's thread while the
     * runner thread reads this on every signal; writing it LAST also publishes the fresh
     * {@link #analysedData} entries to that reader.
     */
    private volatile boolean analyseRun = true;
    private final AnalyseData[] analysedData = new AnalyseData[2];
    /** Replaced from the measurement thread on a hold, from the runner thread in analyze(). */
    private volatile TimeProcessor timeProcessor;

    public TimeCyclicTest(TestResult testResult, TestLogger testLogger, TestRunnerFactory testRunnerFactory, DeviceService deviceService, MotorSafetyController motorSafety) {
        super(testResult, testLogger, testRunnerFactory, deviceService, motorSafety);
    }

    public void setup() {
        requireRecoveryWiring();
        this.analysedData[0] = new AnalyseData();
        this.analysedData[1] = new AnalyseData();

        testContext = new CyclicTestContext(testResult.getId(), testResult.testParameter.upperTurnForce * 1000, testResult.testParameter.lowerTurnForce * 1000, testResult.testParameter.cycleCount);
        initContext();
        targetLowerLimit = testContext.getLowerLimit();
        targetUpperLimit = testContext.getUpperLimit();

        loadCellThread = testRunnerFactory.createLoadCellThread(testContext, deviceService.getLoadCell(),
                runFiles(), this, recovery, gapRecorder);
        loadCellThread.start();

        log("upperShutOffThreshold " + testContext.getUpperLimit() + " Newton");
        log("lowerShutOffThreshold " + testContext.getLowerLimit() + " Newton");
        log("CycleCount " + testContext.getCycleCount());
        log("time cyclic test start");

        awaitLoadCellOrFail();
        log("load cell delivering measurements");

        connectFrequencyInverter();
        energizeForAnalysePhase();
    }

    private void energizeForAnalysePhase() {
        // One energize() block: atomic against the polling thread, and a safe stop the load cell
        // thread already requested wins - see MotorSafetyController#energize.
        motorSafety.energize(drive -> {
            drive.setActionInCaseOfCommunicationError(2); // disable via general enable
            drive.setSpeedReferenceValueAsRpm((int) Math.round(INITIAL_SPEED / (double) SPEED_DIVISOR));
            drive.setSecondSpeedRampTime(INITIAL_RAMP_TIME, INITIAL_RAMP_TIME); // 300ms each
            drive.setControlParameters(true, true, true, null, true);
        });
    }

    /**
     * Runs on the measurement thread. {@code stop()} shuts the scheduler down NOW, dropping the
     * pending direction change that would otherwise fire into a de-energized drive, and the field is
     * cleared because a {@code ScheduledExecutorService} cannot be revived - the resumed run gets a
     * new processor out of {@link #analyze()}.
     */
    @Override
    protected void onHoldEntry() {
        if (timeProcessor != null) {
            timeProcessor.stop();
            timeProcessor = null;
        }
    }

    /**
     * Resuming re-enters the ANALYSE phase instead of picking the timed phase back up: the specimen
     * relaxes and creeps while the hold stands, so the release/pull times measured before the loss
     * no longer describe it. {@link #analyze()} then builds the replacement {@code TimeProcessor}
     * exactly as it does on a fresh run - which is also why {@code TimeProcessor} has no pause(),
     * see {@link TimeProcessor#stop()}.
     */
    @Override
    protected void reinitDriveForResume() {
        analysedData[0] = new AnalyseData();
        analysedData[1] = new AnalyseData();
        analyseRun = true;
        energizeForAnalysePhase();
    }

    @Override
    public void handleSignal(int signal) throws FinishTestException {
        if (this.analyseRun && this.analyze()) {
            this.analyseRun = false;
            double startRampSeconds = testResult.testParameter.startRampSeconds;
            double stopRampSeconds = testResult.testParameter.stopRampSeconds;
            int speedRpm = (int) Math.round(testResult.testParameter.speed / (double) SPEED_DIVISOR);

            motorSafety.withDrive(drive -> {
                if (startRampSeconds > 0 && stopRampSeconds > 0) {
                    drive.setSecondSpeedRampTime((int) (startRampSeconds * RAMP_TIME_MULTIPLIER), (int) (stopRampSeconds * RAMP_TIME_MULTIPLIER));
                } else {
                    drive.setUseSecondRamp(false);
                }

                drive.setSpeedReferenceValueAsRpm(speedRpm);
            });
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
        if (Math.abs(loadCellThread.getMaxValue() - targetUpperLimit) < FORCE_THRESHOLD) {
            log("set start time for release");
            analysedData[index].setAndCheckStartTime(System.currentTimeMillis());
            analysedData[alter].endTime = System.currentTimeMillis();
        }

        float minForceValue = loadCellThread.getMinValue();

        log("Current min value " + minForceValue);
        log("<b>init: start release round<b/>");
        driveRelease();

        if (Math.abs(minForceValue - targetLowerLimit) < FORCE_THRESHOLD) {
            analysedData[index].minForce = minForceValue;
        }

        loadCellThread.setMinValue((float) targetUpperLimit);
    }

    private void handlePullSignal(int index, int alter) {
        if (Math.abs(targetLowerLimit - loadCellThread.getMinValue()) < FORCE_THRESHOLD) {
            log("set start time for pull");
            analysedData[index].setAndCheckStartTime(System.currentTimeMillis());
            analysedData[alter].endTime = System.currentTimeMillis();
        }

        float maxForceValue = loadCellThread.getMaxValue();

        log("Current max value " + maxForceValue);
        log("<b>init: start pull round<b/>");
        drivePull();

        if (Math.abs(maxForceValue - targetUpperLimit) < FORCE_THRESHOLD) {
            analysedData[index].maxForce = maxForceValue;
        }

        loadCellThread.setMaxValue((float) targetLowerLimit);
    }

    /**
     * Analyze the data and calculate the release and pull time
     *
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

        // A TimeProcessor registers itself as a signal listener in its constructor, so a predecessor
        // left standing here would keep scheduling direction changes alongside the new one - two
        // schedules driving one drive. Reached whenever a resume re-enters the analyse phase.
        if (timeProcessor != null) {
            timeProcessor.stop();
        }
        timeProcessor = new TimeProcessor(testContext, releaseTime, pullTime);
        return true;
    }

    @Override
    public void cleanup() {
        super.cleanup();
        if (timeProcessor != null) {
            timeProcessor.stop();
            timeProcessor = null;
        }
    }
}