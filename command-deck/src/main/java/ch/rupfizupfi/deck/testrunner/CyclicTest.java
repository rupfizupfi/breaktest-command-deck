package ch.rupfizupfi.deck.testrunner;

import ch.rupfizupfi.deck.data.TestResult;
import ch.rupfizupfi.deck.device.DeviceService;
import ch.rupfizupfi.deck.testrunner.cyclic.CyclicTestContext;

public class CyclicTest extends AbstractTest {
    protected CyclicTestContext testContext;
    protected double targetLowerLimit;
    protected double targetUpperLimit;

    public CyclicTest(TestResult testResult, TestLogger testLogger, TestRunnerFactory testRunnerFactory, DeviceService deviceService, MotorSafetyController motorSafety) {
        super(testResult, testLogger, testRunnerFactory, deviceService, motorSafety);
    }

    void setup() {
        requireRecoveryWiring();
        testContext = new CyclicTestContext(testResult.getId(), testResult.testParameter.upperTurnForce * 1000, testResult.testParameter.lowerTurnForce * 1000, testResult.testParameter.cycleCount);
        initContext();
        targetLowerLimit = testContext.getLowerLimit();
        targetUpperLimit = testContext.getUpperLimit();

        loadCellThread = testRunnerFactory.createLoadCellThread(testContext, deviceService.getLoadCell(),
                runFiles(), this, gates, gapRecorder);
        loadCellThread.start();

        log("upperShutOffThreshold " + testContext.getUpperLimit() + " Newton");
        log("lowerShutOffThreshold " + testContext.getLowerLimit() + " Newton");
        log("CycleCount " + testContext.getCycleCount());
        log("cyclic test start");

        awaitLoadCellOrFail();
        log("load cell delivering measurements");

        connectFrequencyInverter();
        energizeForRun(true);
    }

    /**
     * @param release initial direction, true = release, false = pull, as {@code Drive#setDirection}
     *                takes it
     */
    private void energizeForRun(boolean release) {
        int speedRpm = (int) Math.round(testResult.testParameter.speed / 0.375);
        double startRampSeconds = testResult.testParameter.startRampSeconds;
        double stopRampSeconds = testResult.testParameter.stopRampSeconds;
        // One energize() block: atomic against the polling thread, and a safe stop the load cell
        // thread already requested wins - see MotorSafetyController#energize.
        motorSafety.energize(drive -> {
            drive.setActionInCaseOfCommunicationError(2); // disable via general enable
            drive.setSpeedReferenceValueAsRpm(speedRpm);
            drive.setDirection(release);
            drive.setGeneralEnable(true);
            drive.setStart(true);

            if (startRampSeconds > 0 && stopRampSeconds > 0) {
                drive.setUseSecondRamp(true);
                drive.setSecondSpeedRampTime((int) (startRampSeconds * 10), (int) (stopRampSeconds * 10));
            }
        });
    }

    @Override
    protected boolean supportsResume() {
        return true;
    }

    @Override
    protected void reinitDriveForResume() {
        energizeForRun(initialResumeDirection());
    }

    /**
     * Toward whichever limit the run was heading for: resuming in the other direction would unload
     * the specimen and lose the cycle the run was in the middle of. The nearer limit stands in for
     * that intent - the drive was de-energized before anything recorded which way it was going.
     */
    private boolean initialResumeDirection() {
        float force = loadCellThread == null ? Float.NaN : loadCellThread.getLastForce();
        if (!Float.isFinite(force)) {
            // Nothing to reason from, so pick the direction that unloads rather than the one that
            // adds load to a specimen whose state is unknown.
            return true;
        }

        boolean nearerToUpper = Math.abs(testContext.getUpperLimit() - force)
                < Math.abs(force - testContext.getLowerLimit());
        return !nearerToUpper;
    }

    void initContext() {
        super.testContext = testContext;
        super.initContext();
    }

    /**
     * No state guard of its own: {@code TestContext}'s dispatch gate already drops every crossing
     * measured while the run is not dispatching, so nothing here can act on a pre-loss force. That
     * is what protects {@code driveIsPull()} below, which queries the drive and throws
     * {@code DriveUnavailableException} once the handle is gone - the state
     * {@code canResume()} already refuses to resume out of.
     */
    @Override
    public void handleSignal(int signal) throws FinishTestException {
        switch (signal) {
            case 0:
                finish();
                break;
            case TestContext.RELEASE_SIGNAL: //upper limit triggered
                if (driveIsPull()) {
                    log("Current min value " + loadCellThread.getMinValue());
                    double diff = targetLowerLimit - loadCellThread.getMinValue();

                    if (diff != 0.0) {
                        testContext.setLowerLimit(Math.max(testContext.getLowerLimit() + diff, targetLowerLimit));
                        log("New lower limit " + testContext.getLowerLimit());
                    }

                    log("change direction to forward");
                    log("CycleCount " + testContext.getCycleCount());

                    driveRelease();
                    loadCellThread.setMinValue((float) targetUpperLimit);
                }
                break;
            case TestContext.PULL_SIGNAL:
                if (driveIsRelease()) {
                    log("Current max value " + loadCellThread.getMaxValue());
                    double diff = targetUpperLimit - loadCellThread.getMaxValue();

                    if (diff != 0.0) {
                        testContext.setUpperLimit(Math.min(testContext.getUpperLimit() + diff, targetUpperLimit));
                        log("New upper limit " + testContext.getUpperLimit());
                    }

                    log("change direction to backword");
                    log("CycleCount " + testContext.getCycleCount());

                    drivePull();
                    loadCellThread.setMaxValue((float) targetLowerLimit);
                    testContext.decrementCycleCount();
                }
                break;
        }
    }

    @Override
    void cleanup() {
        super.cleanup();
        try {
            motorSafety.withDrive(drive -> drive.setUseSecondRamp(false));
        } catch (RuntimeException e) {
            // the safe stop may have closed the handle; ramp state is cosmetic at this point
            log("could not reset second ramp: " + e.getMessage());
        }
    }
}
