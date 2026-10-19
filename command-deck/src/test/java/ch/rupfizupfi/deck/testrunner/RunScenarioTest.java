package ch.rupfizupfi.deck.testrunner;

import ch.rupfizupfi.deck.data.Setting;
import ch.rupfizupfi.deck.data.TestResult;
import ch.rupfizupfi.deck.device.api.DriveLane;
import ch.rupfizupfi.deck.device.api.Measurement;
import ch.rupfizupfi.deck.device.simulated.SimulatedBenchProperties;
import ch.rupfizupfi.deck.device.simulated.SimulatedFault;
import ch.rupfizupfi.deck.rig.RigLimits;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Every run path of today's control code, end to end on the simulated bench, each pinned to a
 * golden of its transition history, run log, gap record and drive write sequence. The contract
 * steps 4-9 of OQ-64 are held to (gate G7): a step that changes a golden on purpose re-records it
 * in its own commit and says so. Harness: {@link SimulatedRunHarness}.
 * <p>
 * Parameters are chosen so the clock cannot reorder anything a golden records: the slow runs make
 * no reversal for the whole scenario, and a fault that must land mid-cycle is armed from a drive
 * hook right after a reversal write, a half cycle away from the next one.
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class RunScenarioTest extends SimulatedRunHarness {

    /** Pulls slowly toward a 30 kN shut-off it never reaches inside a scenario. */
    private static final RunSpec DESTRUCTIVE_SLOW = RunSpec.destructive(50, 1.0, 30.0);
    /**
     * 0.25 kN turn band around the 2.14 kN pretension, 11 noise sigmas from either turn: a half
     * cycle takes ~0.35 s.
     */
    private static final RunSpec CYCLIC_FAST = RunSpec.cyclic(300, 2.0, 2.25, 3.0, 2);
    /** Releases from 2.14 kN toward a 1 kN turn it needs ~10 s to reach: no reversal in a scenario. */
    private static final RunSpec CYCLIC_SLOW = RunSpec.cyclic(50, 1.0, 10.0, 12.0, 5);
    /** Bit-varying for the frozen-sample detector, far below any turn margin a scenario relies on. */
    private static final double QUIET_NOISE_NEWTON = 1.0;

    /**
     * The stiff specimen breaks at 34.2 kN, below this 40 kN shut-off. At the rig's highest
     * admissible speed, so the ~105 mm to the break take ~6 s rather than ~13.
     */
    private static final RunSpec BREAK = RunSpec.destructive(RigLimits.SPEED_UPPER_BOUND_MILLIMETRES_PER_MINUTE, 1.0, 40.0);

    @Autowired
    SimulatedBenchProperties benchProperties;

    /** The break scenarios pull faster than the harness's default rig speed limit admits. */
    private void admitTheBreakSpeed() {
        doReturn(Setting.create(Setting.Key.RIG_MAX_SPEED, RigLimits.SPEED_UPPER_BOUND_MILLIMETRES_PER_MINUTE))
                .when(settings).getSetting(Setting.Key.RIG_MAX_SPEED.getKey());
    }

    // ---- normal finishes ---------------------------------------------------------------------

    /**
     * An upper shut-off just above the pretension: the specimen held, so no suction, and the
     * suction settings are not even read. Until 2026-10-13 a 2 s clock guard kept this one dry.
     */
    @Test
    void destructiveFinishesOnALowUpperShutOffWithoutSuction() {
        Run run = start(RunSpec.destructive(500, 1.0, 2.6));
        run.awaitState(TestState.FINISHED);
        run.awaitEnd();

        verify(settings, never()).getSettingValue(Setting.Key.TESTRUNNER_SUCK);
        verify(suckService, never()).enable();
        assertCommon(run, true);
        assertGolden("destructive-upper-limit-no-suction", record(run));
    }

    /** Load built and the upper shut-off reached with the specimen intact: never a break (owner, 2026-10-13). */
    @Test
    void destructiveUpperShutOffWithTheSpecimenHeldSkipsSuction() {
        Run run = start(RunSpec.destructive(500, 1.0, 7.0));
        run.awaitState(TestState.FINISHED);
        run.awaitEnd();

        verify(settings, never()).getSettingValue(Setting.Key.TESTRUNNER_SUCK);
        verify(suckService, never()).enable();
        assertThat(runLogLines(run)).contains("suction skipped: upper shut-off reached, specimen held");
        assertCommon(run, true);
        assertGolden("destructive-upper-limit-held-no-suction", record(run));
    }

    /**
     * The break: the stiff specimen fractures at 34.2 kN under a 40 kN shut-off, the force collapses
     * to the residual and crosses the lower shut-off, and the suction job runs. Pulled at the
     * highest speed the rig limits admit, so the break comes ~6 s after the energize.
     */
    @Test
    void destructiveBreakBelowTheUpperShutOffStartsSuction() throws InterruptedException {
        CountDownLatch relayOff = new CountDownLatch(1);
        doAnswer(invocation -> {
            Object result = invocation.callRealMethod();
            relayOff.countDown();
            return result;
        }).when(suckService).disable();
        admitTheBreakSpeed();

        Run run = start(BREAK);
        run.awaitState(TestState.RUNNING);
        run.awaitState(TestState.FINISHED);
        run.awaitEnd();

        assertThat(relayOff.await(5, TimeUnit.SECONDS)).as("suction job ran and released the relay").isTrue();
        verify(suckService).enable();
        assertThat(runLogLines(run)).noneMatch(line -> line.startsWith("suction skipped"));
        assertCommon(run, true);
        assertGolden("destructive-break-suction", record(run));
    }

    /**
     * The same break, but no drive handle answers from just before it: the stop escalates to tier 3,
     * the run still ends FINISHED, and the suction stays off with the reason in the run log.
     */
    @Test
    void destructiveBreakWithAnUnconfirmedMotorStopSkipsSuction() {
        // Armed just before the break, not at the energize: a drive silent for 2 s trips its own
        // link-loss stop (P0314, simulated), which would halt the pull before the specimen breaks.
        stream.onceAbove(33_000f, () -> faults.arm(SimulatedFault.DRIVE_UNRESPONSIVE));
        admitTheBreakSpeed();
        Run run = start(BREAK);
        run.awaitState(TestState.RUNNING);
        run.awaitState(TestState.FINISHED);
        run.awaitEnd();

        assertThat(motorSafety.getLastStopResult().needsOperatorAttention()).isTrue();
        verify(settings, never()).getSettingValue(Setting.Key.TESTRUNNER_SUCK);
        verify(suckService, never()).enable();
        List<String> runLog = runLogLines(run);
        assertThat(runLog).anyMatch(line -> line.startsWith("WARNING: motor stop could not be confirmed"));
        assertThat(runLog).contains("suction skipped: motor stop unconfirmed, use E-stop");
        faults.clear(SimulatedFault.DRIVE_UNRESPONSIVE);
        // Tier 3 leaves the plant energized; releaseBench de-energizes it for the next scenario.
        assertForceCsv(run, true);
        assertGolden("destructive-break-stop-unconfirmed-no-suction", record(run));
    }

    @Test
    void cyclicCompletesItsCyclesWithLimitAdaptation() {
        Run run = start(CYCLIC_FAST.withRamps(1.0, 1.0));
        run.awaitState(TestState.FINISHED);
        run.awaitEnd();

        assertCommon(run, true);
        assertGolden("cyclic-completes", record(run));
    }

    /** The pretension sits four noise sigmas above the upper turn, so calibration starts on a release round. */
    @Test
    void timeCyclicCalibratesThenTurnsOnTime() {
        Run run = start(RunSpec.timeCyclic(100, 2.04, 2.10, 3.0, 3));
        run.awaitState(TestState.FINISHED);
        run.awaitEnd();

        assertCommon(run, true);
        assertGolden("time-cyclic-completes", record(run));
    }

    // ---- operator commands -------------------------------------------------------------------

    @Test
    void operatorStopWhileRunningFinishesTheRun() {
        Run run = start(DESTRUCTIVE_SLOW);
        run.awaitState(TestState.RUNNING);
        run.runner.stopThread();
        run.awaitEnd();

        assertThat(run.state()).isEqualTo(TestState.FINISHED);
        verify(suckService, never()).enable();
        assertCommon(run, true);
        assertGolden("operator-stop-running", record(run));
    }

    @Test
    void operatorAbortWhileRunningAbortsTheRun() {
        Run run = start(DESTRUCTIVE_SLOW);
        run.awaitState(TestState.RUNNING);
        TestRunnerThread.ResumeResponse response = run.runner.abortTest(OPERATOR);
        assertThat(response.accepted()).isTrue();
        run.awaitEnd();

        assertThat(run.state()).isEqualTo(TestState.ABORTED);
        verify(suckService, never()).enable();
        assertCommon(run, true);
        assertGolden("operator-abort-running", record(run));
    }

    /** I1, I12: a Stop latched during STARTING refuses the energize that setup reaches afterwards. */
    @Test
    void stopDuringStartingAbortsWithoutAnEnergize() throws InterruptedException {
        CountDownLatch parked = new CountDownLatch(1);
        CountDownLatch latched = new CountDownLatch(1);
        motorSafety.onLatch(latched::countDown);
        // The startup check's drive read, held until the operator's Stop has latched.
        drive.before(onRunner("getControlParameters", ""), call -> {
            parked.countDown();
            awaitQuietly(latched);
        });

        Run run = start(DESTRUCTIVE_SLOW);
        assertThat(parked.await(STATE_TIMEOUT_MS, TimeUnit.MILLISECONDS)).isTrue();
        assertThat(run.state()).isEqualTo(TestState.STARTING);
        run.runner.stopThread();
        run.awaitEnd();

        assertThat(run.state()).isEqualTo(TestState.ABORTED);
        assertThat(drive.calls()).noneMatch(c -> c.method().equals("setActionInCaseOfCommunicationError"));
        assertBenchDeEnergized();
        assertGolden("stop-during-starting", record(run));
    }

    // ---- sensor loss -------------------------------------------------------------------------

    /** I3, I10, I16: hold, recover, resume, and the run completes its cycles in one CSV. */
    @Test
    void cyclicSensorLossHoldsThenResumesAndCompletes() {
        drive.after(nth(1, onRunner("setDirection", "false")), call -> faults.arm(SimulatedFault.LOAD_CELL_SILENT));
        // Slow and wide, so the ~60 N the motor travels blind before the trip stays far below the
        // band's midpoint, which decides the resume direction.
        Run run = start(RunSpec.cyclic(100, 1.9, 2.4, 3.0, 2));
        run.awaitState(TestState.SAFE_HOLD);
        faults.clear(SimulatedFault.LOAD_CELL_SILENT);
        awaitLog("sensor recovery after", 1);

        assertThat(run.runner.resumeTest(OPERATOR).accepted()).isTrue();
        run.awaitState(TestState.FINISHED);
        run.awaitEnd();

        assertStopBeforeIncident(run, 1);
        assertCommon(run, false);
        assertGolden("cyclic-loss-resume-completes", record(run));
    }

    @Test
    void destructiveSensorLossIsAbortOnly() {
        Run run = start(DESTRUCTIVE_SLOW);
        run.awaitState(TestState.RUNNING);
        faults.arm(SimulatedFault.LOAD_CELL_SILENT);
        run.awaitState(TestState.SAFE_HOLD);
        faults.clear(SimulatedFault.LOAD_CELL_SILENT);
        awaitLog("sensor recovery after", 1);

        TestRunnerThread.ResumeResponse refused = run.runner.resumeTest(OPERATOR);
        assertThat(refused.accepted()).isFalse();
        assertThat(refused.detail()).contains("recovery gates refuse");
        assertThat(run.runner.abortTest(OPERATOR).accepted()).isTrue();
        run.awaitEnd();

        assertThat(run.state()).isEqualTo(TestState.ABORTED);
        assertStopBeforeIncident(run, 1);
        assertCommon(run, false);
        assertGolden("destructive-loss-abort-only", record(run));
    }

    /**
     * A frozen cell ends RUNNING as a sensor loss with the frozen cause. No golden: the trip's
     * sample count and duration are clock-dependent, and the path after the trip is
     * {@code destructive-loss-abort-only}'s.
     */
    @Test
    void aFrozenLoadCellIsASensorLoss() {
        Run run = start(DESTRUCTIVE_SLOW);
        run.awaitState(TestState.RUNNING);
        faults.arm(SimulatedFault.LOAD_CELL_FROZEN);
        run.awaitState(TestState.SAFE_HOLD);
        faults.clear(SimulatedFault.LOAD_CELL_FROZEN);
        awaitLog("sensor recovery after", 1);
        assertThat(run.runner.abortTest(OPERATOR).accepted()).isTrue();
        run.awaitEnd();

        assertThat(run.state()).isEqualTo(TestState.ABORTED);
        TestStateMachine.TransitionRecord lost = run.machine.history().stream()
                .filter(r -> r.to() == TestState.SENSOR_LOST).findFirst().orElseThrow();
        // The count, normally; a starved CI thread can stretch the drains past the time bound first.
        assertThat(lost.reason()).startsWith("force frozen at ")
                .contains(" consecutive bit-identical samples over ");
        assertStopBeforeIncident(run, 1);
        assertCommon(run, false);
    }

    @Test
    void anUnattendedHoldIsAbortedByTheSafeHoldTimer() {
        recoveryProperties.setSafeHoldTimeoutMillis(1000);
        recoveryProperties.setMaxHoldForResumeMillis(1000);
        Run run = start(CYCLIC_SLOW);
        run.awaitState(TestState.RUNNING);
        faults.arm(SimulatedFault.LOAD_CELL_SILENT);
        run.awaitState(TestState.SAFE_HOLD);
        faults.clear(SimulatedFault.LOAD_CELL_SILENT);
        awaitLog("sensor recovery after", 1);
        run.awaitState(TestState.ABORTED);
        run.awaitEnd();

        assertStopBeforeIncident(run, 1);
        assertCommon(run, false);
        assertGolden("hold-timeout", record(run));
    }

    /** The loss budget: one resume, and the second loss of the flapping stream is refused one. */
    @Test
    void aFlappingStreamExhaustsTheLossBudget() {
        recoveryProperties.setMaxLossesPerRun(1);
        Run run = start(CYCLIC_SLOW);
        run.awaitState(TestState.RUNNING);
        faults.arm(SimulatedFault.LOAD_CELL_FLAPPING);
        run.awaitState(TestState.SAFE_HOLD, 1);
        awaitLog("sensor recovery after", 1);
        assertThat(run.runner.resumeTest(OPERATOR).accepted()).isTrue();
        run.awaitState(TestState.RUNNING, 2);

        run.awaitState(TestState.SAFE_HOLD, 2);
        awaitLog("sensor recovery after", 2);
        TestRunnerThread.ResumeResponse refused = run.runner.resumeTest(OPERATOR);
        assertThat(refused.accepted()).isFalse();
        // Today's reason text, unchanged by the move of the gates into the loop (OQ-64 step 6).
        assertThat(refused.detail()).isEqualTo("the run is holding, but its recovery gates refuse a resume");
        assertThat(run.runner.abortTest(OPERATOR).accepted()).isTrue();
        run.awaitEnd();

        assertThat(run.state()).isEqualTo(TestState.ABORTED);
        assertStopBeforeIncident(run, 1);
        assertStopBeforeIncident(run, 2);
        assertCommon(run, false);
        assertGolden("flapping-loss-budget", record(run));
    }

    /**
     * I11: a loss while a resume is in flight takes RESUMING to SENSOR_LOST, the superseded resume
     * never clears the latch or energizes, and the next hold is past the loss budget. Since OQ-64
     * step 6 the first gap closes SUPERSEDED when the second opens; it used to stay HOLDING. The
     * measurement thread commits SENSOR_LOST, but the second SAFE_HOLD is the loop's hold entry,
     * which waits for the parked resume - so the pause is released on SENSOR_LOST.
     */
    @Test
    void aLossDuringResumeSupersedesItAndTheBudgetRefusesTheNext() throws InterruptedException {
        recoveryProperties.setMaxLossesPerRun(1);
        Run run = start(CYCLIC_SLOW);
        run.awaitState(TestState.RUNNING);
        faults.arm(SimulatedFault.LOAD_CELL_SILENT);
        run.awaitState(TestState.SAFE_HOLD);
        faults.clear(SimulatedFault.LOAD_CELL_SILENT);
        awaitLog("sensor recovery after", 1);

        ProbedMotorSafetyController.Pause pause = motorSafety.pauseResumeAtStopRead();
        assertThat(run.runner.resumeTest(OPERATOR).accepted()).isTrue();
        assertThat(pause.awaitReached(STATE_TIMEOUT_MS)).isTrue();
        faults.arm(SimulatedFault.LOAD_CELL_SILENT);
        run.awaitState(TestState.SENSOR_LOST, 2);
        pause.release();
        run.awaitState(TestState.SAFE_HOLD, 2);
        faults.clear(SimulatedFault.LOAD_CELL_SILENT);
        awaitLog("sensor recovery after", 2);

        assertThat(run.runner.resumeTest(OPERATOR).accepted()).isFalse();
        assertThat(run.runner.abortTest(OPERATOR).accepted()).isTrue();
        run.awaitEnd();

        assertThat(run.state()).isEqualTo(TestState.ABORTED);
        assertStopBeforeIncident(run, 2);
        assertCommon(run, false);
        assertGolden("loss-during-resume", record(run));
    }

    /**
     * I11, I12: Stop pressed while the resume is between its fresh-data proof and its energize.
     * Since OQ-64 step 6 the gap closes ABORTED, with the incident's stop; it used to stay HOLDING.
     */
    @Test
    void aStopDuringAnInFlightResumeKeepsTheDriveDeEnergized() throws InterruptedException {
        Run run = start(CYCLIC_SLOW);
        run.awaitState(TestState.RUNNING);
        faults.arm(SimulatedFault.LOAD_CELL_SILENT);
        run.awaitState(TestState.SAFE_HOLD);
        faults.clear(SimulatedFault.LOAD_CELL_SILENT);
        awaitLog("sensor recovery after", 1);

        ProbedMotorSafetyController.Pause pause = motorSafety.pauseResumeAtStopRead();
        assertThat(run.runner.resumeTest(OPERATOR).accepted()).isTrue();
        assertThat(pause.awaitReached(STATE_TIMEOUT_MS)).isTrue();
        // Released by the Stop's own transition, before it latches: the resume then finds the run
        // out of RESUMING and must leave the drive alone.
        run.machine.addListener(record -> {
            if (record.to() == TestState.ABORTED) {
                pause.release();
            }
        });
        run.runner.stopThread();
        run.awaitEnd();

        assertThat(run.state()).isEqualTo(TestState.ABORTED);
        assertCommon(run, false);
        assertGolden("stop-during-resume", record(run));
    }

    /**
     * I11, the other branch (OQ-64 step 6): Stop pressed after the resume's energize and before its
     * {@code transition(RUNNING)}. The resume finds the run ABORTED and undoes its own energize with
     * one safe stop on the loop, before the Stop's teardown. Pinned from inside the energize: the
     * hook holds the resume at its last write until the Stop, on a thread of its own, has latched.
     */
    @Test
    void aStopAfterTheResumesEnergizeIsUndoneByTheResumesOwnSafeStop() throws InterruptedException {
        Run run = start(CYCLIC_SLOW);
        run.awaitState(TestState.RUNNING);
        faults.arm(SimulatedFault.LOAD_CELL_SILENT);
        run.awaitState(TestState.SAFE_HOLD);
        faults.clear(SimulatedFault.LOAD_CELL_SILENT);
        awaitLog("sensor recovery after", 1);

        CountDownLatch latched = new CountDownLatch(1);
        motorSafety.onLatch(latched::countDown);
        List<Thread> stopper = new java.util.concurrent.CopyOnWriteArrayList<>();
        // Registered now, so the first energize it sees is the resume's: since D3 (OQ-64 step 7) the
        // energize is one control word carrying start and general enable together.
        drive.after(SimulatedRunHarness::isEnergizeOnRunner, call -> {
            stopper.add(Thread.ofPlatform().name("operator-stop").start(run.runner::stopThread));
            awaitQuietly(latched);
        });
        assertThat(run.runner.resumeTest(OPERATOR).accepted()).isTrue();
        run.awaitEnd();
        assertThat(stopper).hasSize(1);
        stopper.getFirst().join(STATE_TIMEOUT_MS);

        assertThat(run.state()).isEqualTo(TestState.ABORTED);
        assertThat(run.count(TestState.RUNNING)).as("the resume never reached RUNNING").isEqualTo(1);
        // The undo: a de-energize on the loop after the resume's energize write.
        List<RecordingDriveProvider.DriveCall> calls = drive.calls();
        int resumeStart = lastIndexOf(calls, SimulatedRunHarness::isEnergizeOnRunner);
        int undo = indexOf(calls, resumeStart, c -> isDeEnergize(c) && c.thread().equals("TestRunnerThread"));
        assertThat(undo).as("a de-energize after the resume's energize").isGreaterThan(resumeStart);
        assertCommon(run, false);
        assertGolden("stop-after-resume-energize", record(run));
    }

    // ---- hard trips --------------------------------------------------------------------------

    /** I9: one plausible reading above the ceiling ends the run FAULT with a verified stop. */
    @Test
    void aForceCeilingCrossingEndsTheRunInFault() {
        Run run = start(CYCLIC_SLOW);
        run.awaitState(TestState.RUNNING);
        stream.injectOnce(new Measurement(13_000f, System.currentTimeMillis()));
        run.awaitState(TestState.FAULT);
        run.awaitEnd();

        assertCommon(run, true);
        assertGolden("ceiling-trip", record(run));
    }

    /** I9: the ceiling is checked while the recovery gate is still scoring a reconnected stream. */
    @Test
    void theCeilingIsCheckedWhileGating() {
        Run run = start(CYCLIC_SLOW);
        run.awaitState(TestState.RUNNING);
        faults.arm(SimulatedFault.LOAD_CELL_SILENT);
        run.awaitState(TestState.SAFE_HOLD);
        faults.arm(SimulatedFault.LOAD_CELL_RECONNECT_GARBAGE);
        faults.clear(SimulatedFault.LOAD_CELL_SILENT);
        run.awaitState(TestState.FAULT);
        run.awaitEnd();

        assertCommon(run, false);
        // The reconnector's verdict arrives when the teardown completes its gate, racing the log's close.
        assertGolden("ceiling-trip-while-gating", record(run, line -> line.startsWith("sensor recovery"),
                "lines starting 'sensor recovery' (the reconnector's verdict races the run log's close)"));
    }

    /** The measurement loop itself dies: de-energize first, then FAULT. */
    @Test
    void aDeadMeasurementLoopEndsTheRunInFault() {
        Run run = start(DESTRUCTIVE_SLOW);
        run.awaitState(TestState.RUNNING);
        stream.injectOnce(null);
        run.awaitState(TestState.FAULT);
        run.awaitEnd();

        assertCommon(run, false);
        assertGolden("watchdog-failure", record(run));
    }

    // ---- drive faults ------------------------------------------------------------------------

    @Test
    void aStaleDriveHandleIsStoppedOnTier2() {
        Run run = start(DESTRUCTIVE_SLOW);
        run.awaitState(TestState.RUNNING);
        faults.arm(SimulatedFault.DRIVE_STALE_HANDLE);
        run.runner.stopThread();
        run.awaitEnd();

        assertThat(run.state()).isEqualTo(TestState.FINISHED);
        assertThat(motorSafety.getLastStopResult().tier()).isEqualTo(SafeStopResult.Tier.FRESH_HANDLE);
        assertThat(motorSafety.getLastStopResult().verified()).isTrue();
        faults.clear(SimulatedFault.DRIVE_STALE_HANDLE);
        assertCommon(run, true);
        assertGolden("drive-stale-handle-tier2", record(run));
    }

    @Test
    void anUnresponsiveDriveFaultsTheRunEscalatesToTier3AndRefusesResume() {
        drive.after(nth(1, onRunner("setDirection", "false")), call -> faults.arm(SimulatedFault.DRIVE_UNRESPONSIVE));
        // A ceiling the plant cannot reach before teardown: tier 3 leaves the motor pulling until the
        // drive's own link-loss stop fires.
        Run run = start(RunSpec.cyclic(300, 2.0, 2.25, 10.0, 5));
        run.awaitState(TestState.FAULT);
        run.awaitEnd();

        assertThat(motorSafety.getLastStopResult().tier()).isEqualTo(SafeStopResult.Tier.OPERATOR_ESCALATION);
        // Tier 3 is never reported as a verified stop: the motor may run on until the drive's own
        // link-loss stop (P0314) fires, and the deck cannot know when. The run says so and only so.
        assertThat(motorSafety.getLastStopResult().verified()).isFalse();
        assertThat(run.state()).isEqualTo(TestState.FAULT);
        List<String> runLog = runLogLines(run);
        assertThat(runLog).anyMatch(line -> line.startsWith("WARNING: motor stop could not be confirmed")
                && line.endsWith("- use the physical E-stop"));
        assertThat(runLog).noneMatch(line -> line.startsWith("motor de-energized"));
        TestRunnerThread.ResumeResponse refused = run.runner.resumeTest(OPERATOR);
        assertThat(refused.accepted()).isFalse();
        assertThat(refused.detail()).contains("only possible from a safe hold");
        faults.clear(SimulatedFault.DRIVE_UNRESPONSIVE);
        assertForceCsv(run, true);
        assertGolden("drive-unresponsive-tier3", record(run));
    }

    /**
     * BL-135/BL-148: the run's energizing write wedges and never ends on its own (the wedge at its
     * one-day maximum). Tier 1's stops queue behind it on the same handle and miss their
     * deadlines; tier 2's close of that handle gives up at the 1500 ms close bound, so no second
     * session opens beside the transfer and tier 3 is raised at once instead of behind the close.
     * There is no fresh-handle stop for the energizing write to land after; once the transfer ends,
     * the stops queued behind it on its own handle follow it, and only then does the port close.
     * No golden: how many of tier 1's re-sends queue behind the wedge is clock-driven.
     */
    @Test
    void aDriveTransferWedgedPastTheCloseBoundEscalatesToTier3WithoutASecondSession() {
        long wedgeMillis = benchProperties.getWedgeMillis();
        benchProperties.setWedgeMillis(Long.MAX_VALUE);
        try {
            var wedgedHandle = new AtomicReference<String>();
            var closeBegan = new AtomicLong();
            drive.before(nth(1, SimulatedRunHarness::isEnergizeOnRunner), call -> {
                wedgedHandle.set(call.handle());
                faults.arm(SimulatedFault.DRIVE_TRANSACTION_WEDGED);
            });
            drive.before(call -> call.method().equals("close") && call.handle().equals(wedgedHandle.get()),
                    call -> closeBegan.compareAndSet(0, System.nanoTime()));

            Run run = start(RunSpec.cyclic(300, 2.0, 2.25, 10.0, 5));
            // closeBegan first: a result left by an earlier scenario stays until this run clears it.
            awaitCondition(() -> closeBegan.get() != 0 && motorSafety.getLastStopResult() != null,
                    30_000, "the run's safe stop");
            long escalatedAt = System.nanoTime();
            run.awaitState(TestState.FAULT);
            run.awaitEnd();

            SafeStopResult result = motorSafety.getLastStopResult();
            assertThat(result.tier()).isEqualTo(SafeStopResult.Tier.OPERATOR_ESCALATION);
            assertThat(result.verified()).isFalse();
            assertThat(result.detail()).contains("tier 2 (FRESH_HANDLE) not attempted", "still in flight",
                    "USE THE PHYSICAL E-STOP");
            assertThat(closeBegan.get()).as("tier 2 closed the wedged handle").isNotZero();
            assertThat(TimeUnit.NANOSECONDS.toMillis(escalatedAt - closeBegan.get()))
                    .as("tier 3 raised within the close bound plus slack, never behind the wedge")
                    .isLessThanOrEqualTo(DriveLane.STOP_DEADLINE_MILLIS + 500);

            List<RecordingDriveProvider.DriveCall> calls = drive.calls();
            int energize = indexOf(calls, -1, SimulatedRunHarness::isEnergizeOnRunner);
            assertThat(energize).as("the energizing write").isNotNegative();
            assertThat(calls.get(energize).threw()).as("its caller gave up at the CONTROL deadline")
                    .isEqualTo("DriveDeadlineException");
            assertThat(calls).as("tier 2's close of the wedged handle")
                    .anyMatch(c -> c.handle().equals(wedgedHandle.get()) && c.method().equals("close")
                            && "DriveClosePendingException".equals(c.threw()));
            assertThat(calls.subList(energize, calls.size())).as("no handle opened beside the wedged one")
                    .noneMatch(c -> c.method().equals("open"));
            assertThat(benchControlWord().get("start")).as("the wedged energizing write has not landed")
                    .isFalse();

            bench.endWedge();
            // The next connect retries the pending close and opens only once the old handle's I/O
            // thread has ended: the wedged write landed, then the stops queued behind it, then the
            // port closed. Nothing else writes meanwhile, so a de-energized plant now means the
            // stops landed after the energizing write.
            var inverter = deviceService.getFrequencyInverter();
            awaitCondition(() -> {
                try {
                    inverter.connect();
                    return true;
                } catch (RuntimeException stillHeld) {
                    return false;
                }
            }, 10_000, "the wedged handle to close its port");
            inverter.disconnect();
            assertBenchDeEnergized();
        } finally {
            benchProperties.setWedgeMillis(wedgeMillis);
        }
    }

    // ---- refused starts ----------------------------------------------------------------------

    /** I7: a Start while the boot de-energize is pending is refused past the bound, touching nothing. */
    @Test
    void aStartIsRefusedWhileTheBootDeEnergizeIsPending() {
        TestRunnerThread runner = testRunnerFactory.createTestRunnerThread();
        runner.bootDeEnergizeWaitMs = 100;
        TestResult result = persist(DESTRUCTIVE_SLOW);
        CountDownLatch token = BootDeEnergizeGate.begin();
        try {
            assertThatThrownBy(() -> runner.startThread(result))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("the bench is still initialising");
        } finally {
            BootDeEnergizeGate.complete(token);
        }
        assertThat(runner.isRunning()).isFalse();
        assertThat(runner.snapshot()).isNull();
        assertThat(drive.calls()).isEmpty();
    }

    @Test
    void aSecondStartIsRefusedWhileARunIsActiveAndTheSameStartIsANoOp() {
        Run run = start(DESTRUCTIVE_SLOW);
        run.awaitState(TestState.RUNNING);
        TestResult other = persist(DESTRUCTIVE_SLOW);

        assertThatThrownBy(() -> run.runner.startThread(other))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("is active on the bench");
        run.runner.startThread(run.result);
        assertThat(run.machine.history()).hasSize(2);

        run.runner.stopThread();
        run.awaitEnd();
        assertThat(run.state()).isEqualTo(TestState.FINISHED);
    }

    /** I21: a parameter the bench cannot run faults before any device is touched. */
    @Test
    void anUnrunnableParameterFaultsBeforeAnyDevice() {
        Run run = start(RunSpec.destructive(0, 1.0, 30.0));
        run.awaitState(TestState.FAULT);
        run.awaitEnd();

        assertThat(drive.calls()).isEmpty();
        assertThat(stream.delivered()).isEmpty();
        assertGolden("unrunnable-parameter", record(run));
    }

    // ---- time-cyclic incident ----------------------------------------------------------------

    /**
     * I17: a loss in the timed phase stops the timed processor before the hold, no timed reversal
     * reaches the stopped drive, and the resumed run calibrates again before turning on time.
     */
    @Test
    void aTimeCyclicHoldStopsTimedTurnsAndTheResumeRecalibrates() {
        // Run speed half the calibration speed: the scaled turn times double, so the next timed turn
        // is a second away when the watchdog needs ~0.4 s to enter the hold.
        drive.after(nth(5, onRunner("setDirection", "false").or(onRunner("setDirection", "true"))),
                call -> faults.arm(SimulatedFault.LOAD_CELL_SILENT));
        Run run = start(RunSpec.timeCyclic(25, 2.04, 2.10, 3.0, 2));
        run.awaitState(TestState.SAFE_HOLD);
        faults.clear(SimulatedFault.LOAD_CELL_SILENT);
        awaitLog("sensor recovery after", 1);

        // The loss lands just after a timed release turn, so the plant stands 30-55 N under the
        // 2.10 kN upper turn, in a 60 N band. The resume re-energizes releasing, and the expected
        // first crossing is the lower one; at the bench's 10 N noise one sample in the few dozen
        // read before it gets there clears the upper turn about once in 40 runs, and the correct
        // answer to that reading is a release round - a different, equally valid golden. Same on
        // the pre-step-5 code (OQ-64 step 5 flake hunt: 1/81 there, 2/41 on step 5, identical
        // diff). So from the resume until the first post-resume reversal (the pull) the noise is
        // cut to 1 N: still bit-varying for the frozen detector, 30 sigmas under the upper turn.
        // The loss, the hold, the gate and everything after that reversal see the normal noise.
        double noise = benchProperties.getNoiseNewton();
        drive.after(onRunner("setDirection", "false"), call -> benchProperties.setNoiseNewton(noise));
        try {
            benchProperties.setNoiseNewton(QUIET_NOISE_NEWTON);
            assertThat(run.runner.resumeTest(OPERATOR).accepted()).isTrue();
            run.awaitState(TestState.FINISHED);
            run.awaitEnd();
        } finally {
            benchProperties.setNoiseNewton(noise);
        }

        assertStopBeforeIncident(run, 1);
        assertCommon(run, false);
        assertGolden("time-cyclic-loss-recalibrates", record(run));
    }

    // ---- helpers -----------------------------------------------------------------------------

    private void assertCommon(Run run, boolean contiguousCsv) {
        assertForceCsv(run, contiguousCsv);
        assertEnergizedOnlyAfterFirstSample(run);
        assertBenchDeEnergized();
    }

    private static int lastIndexOf(List<RecordingDriveProvider.DriveCall> calls,
                                   Predicate<RecordingDriveProvider.DriveCall> match) {
        for (int i = calls.size() - 1; i >= 0; i--) {
            if (match.test(calls.get(i))) {
                return i;
            }
        }
        return -1;
    }

    private static int indexOf(List<RecordingDriveProvider.DriveCall> calls, int after,
                               Predicate<RecordingDriveProvider.DriveCall> match) {
        for (int i = after + 1; i < calls.size(); i++) {
            if (match.test(calls.get(i))) {
                return i;
            }
        }
        return -1;
    }

    private static List<String> runLogLines(Run run) {
        try {
            return java.nio.file.Files.readAllLines(run.file("_test.log"), java.nio.charset.StandardCharsets.UTF_8);
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await(STATE_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
