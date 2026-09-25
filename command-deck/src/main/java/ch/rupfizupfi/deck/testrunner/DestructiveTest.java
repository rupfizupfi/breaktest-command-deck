package ch.rupfizupfi.deck.testrunner;

import ch.rupfizupfi.deck.data.Setting;
import ch.rupfizupfi.deck.data.TestResult;
import ch.rupfizupfi.deck.device.DeviceService;

public class DestructiveTest extends AbstractTest {
    /**
     * Whether the run ended because the measured force crossed a shut-off threshold, which is the
     * only thing that stands in for a break. Starts false so any path reaching {@link #finish()}
     * without a force limit having been crossed - a stop request, a watchdog trip, a cleanup that
     * runs twice - leaves the suction system alone.
     */
    private volatile boolean finishedOnForceLimit = false;

    public DestructiveTest(TestResult testResult, TestLogger testLogger, TestRunnerFactory testRunnerFactory, DeviceService deviceService, MotorSafetyController motorSafety) {
        super(testResult, testLogger, testRunnerFactory, deviceService, motorSafety);
    }

    /**
     * Abort-only on a sensor loss: a blind coast-down voids the single-pull curve, so there is no
     * defensible way to continue one. The run still enters SAFE_HOLD rather than aborting straight
     * away, so the operator sees the banner and the reason; the hold timer ends it if nobody acts.
     */
    @Override
    protected boolean supportsResume() {
        return false;
    }

    void setup() {
        requireRecoveryWiring();
        testContext = new TestContext(testResult.getId(), testResult.testParameter.upperShutOffThreshold * 1000, testResult.testParameter.lowerShutOffThreshold * 1000);
        initContext();
        loadCellThread = testRunnerFactory.createLoadCellThread(testContext, deviceService.getLoadCell(),
                runFiles(), this, gates, gapRecorder);
        loadCellThread.start();

        log("upperShutOffThreshold " + testContext.getUpperLimit() + " Newton");
        log("lowerShutOffThreshold " + testContext.getLowerLimit() + " Newton");
        log("Destructive test start");

        awaitLoadCellOrFail();
        log("load cell delivering measurements");

        connectFrequencyInverter();
        int speedRpm = (int) Math.round(testResult.testParameter.speed / 0.375);
        // One energize() block: atomic against the polling thread, and a safe stop the load cell
        // thread already requested wins - see MotorSafetyController#energize.
        motorSafety.energize(drive -> {
            drive.setActionInCaseOfCommunicationError(2); // disable via general enable
            drive.setSpeedReferenceValueAsRpm(speedRpm);
            drive.setDirection(false); // pull
            // a previous cyclic run may have left the second ramp enabled in the drive
            drive.setUseSecondRamp(false);
            drive.setGeneralEnable(true);
            drive.setStart(true);
        });
    }

    /**
     * Only the three known signals end a destructive run, and not for the same reason. Crossing
     * either shut-off threshold IS this test's shut-off and stands in for the specimen letting go.
     * Signal 0 is a stop request or a watchdog trip - the load cell watchdog sends it on sensor loss
     * - and must not be read as a break, because nothing measured one.
     * <p>
     * Anything else is logged and ignored rather than treated as an ending: with dispatch gating and
     * a resume path in the run's lifecycle, "any signal finishes the test" is no longer a safe
     * reading of an unrecognised value.
     */
    @Override
    public void handleSignal(int signal) throws FinishTestException {
        switch (signal) {
            case 0 -> finish();
            case TestContext.RELEASE_SIGNAL, TestContext.PULL_SIGNAL -> {
                finishedOnForceLimit = true;
                finish();
            }
            default -> log("ignoring unexpected signal " + signal);
        }
    }

    @Override
    void finish() throws FinishTestException {
        // The elapsed-time guard is kept: it suppresses the suction on a run that ends almost
        // immediately, which is a misconfiguration rather than a break. It is not sufficient on its
        // own, though - a sensor lost an hour into a run also clears it, and there the machine may
        // still hold an intact specimen under load. An abort is excluded for the same reason: there
        // the force disappeared, it did not fall, so no break was ever confirmed.
        if (finishedOnForceLimit && !aborted && System.currentTimeMillis() - startTime > 2000) {
            var settingsRepository = this.deviceService.getSettingRepository();
            try {
                if (settingsRepository.getSettingValue(Setting.Key.TESTRUNNER_SUCK)) {
                    testRunnerFactory.createSuckJob(
                            settingsRepository.getSettingValue(Setting.Key.TESTRUNNER_SUCK_DURATION)).start();
                }
            } catch (Exception e) {
                log("could not start the suction job: " + e.getMessage());
            }
        }

        super.finish();
    }
}
