package ch.rupfizupfi.deck.testrunner.program;

import ch.rupfizupfi.deck.data.TestParameter;
import ch.rupfizupfi.deck.rig.RigLimits;
import ch.rupfizupfi.deck.testrunner.RunParameters;
import ch.rupfizupfi.deck.testrunner.run.Direction;
import ch.rupfizupfi.deck.testrunner.run.Limit;
import ch.rupfizupfi.deck.testrunner.run.RunEvent;
import ch.rupfizupfi.deck.testrunner.run.Verdict;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The time-cyclic program as the composition of its phases (OQ-64 step 8): calibration hands over
 * to the timed phase, a hold entry closes the timed phase and recalibrates, a resume calibrates
 * again on the calibration setup. Every call is on the test thread, as on the loop thread, so the
 * interleavings the old epoch guarded (a hold entering inside an analysis or a reversal) cannot be
 * written here any more: the loop never runs a hold entry inside a program call.
 */
class TimeCyclicProgramTest {

    private static final int RUN_SPEED = 100;

    private TimeCyclicProgram program;
    private FakeRunControl control;
    private long now = 1_000;

    @BeforeEach
    void createProgram() {
        TestParameter parameter = new TestParameter();
        parameter.speed = RUN_SPEED;
        parameter.upperTurnForce = 1.0;
        parameter.lowerTurnForce = 0.1;
        parameter.upperShutOffThreshold = 3.0;
        parameter.cycleCount = 10;
        parameter.startRampSeconds = 0.0;
        parameter.stopRampSeconds = 0.0;
        program = new TimeCyclicProgram(parameter, RigLimits.DEFAULTS, () -> now);
        control = new FakeRunControl(program.limits()).energized(program.startup());
        // Both turns read as reached, so every calibration turn records a time.
        control.max = 1000f;
        control.min = 100f;
    }

    @AfterEach
    void closePhase() {
        program.teardown(control);
    }

    /** Three calibration crossings, a second apart, measure both travel times; the next turn hands over. */
    private void measureBothTravelTimes() {
        program.onLimit(Limit.UPPER, control);
        now += 1000;
        program.onLimit(Limit.LOWER, control);
        now += 1000;
        program.onLimit(Limit.UPPER, control);
        assertThat(program.phase()).isInstanceOf(CalibrationPhase.class);
    }

    @Test
    void itCalibratesReleasingAtTheAnalyseSpeedOnTheShortSecondRampAndResumesIntoCalibration() {
        assertThat(program.startup()).isEqualTo(
                new DriveSetup(RunParameters.toMotorRpm(50), Direction.RELEASE, new Ramp(3, 3)));
        assertThat(program.name()).isEqualTo("TimeCyclicTest");
        assertThat(program.resumePolicy()).isInstanceOf(RestartCalibration.class);
        assertThat(program.resumePolicy().supportsResume()).isTrue();
        assertThat(program.phase()).isInstanceOf(CalibrationPhase.class);
    }

    @Test
    void anUndisturbedCalibrationHandsOverAndSwitchesToTheRunSpeedAfterReversing() {
        measureBothTravelTimes();
        control.calls.clear();

        assertThat(program.onLimit(Limit.LOWER, control)).isEqualTo(Verdict.CONTINUE);

        assertThat(program.phase()).isInstanceOf(TimedCyclePhase.class);
        int reversal = control.calls.indexOf("turn PULL");
        int speed = control.calls.indexOf("runPhaseSpeed " + RunParameters.toMotorRpm(RUN_SPEED) + " null");
        int analysisLog = control.calls.indexOf("log Release time: 1000 ms");
        assertThat(reversal).isNotNegative().isLessThan(speed);
        assertThat(speed).isLessThan(analysisLog);
        // 1000 ms at the analyse speed of 50 mm/min is 500 ms at 100 mm/min.
        assertThat(control.logLines()).contains("Adapted Release time: 500 ms", "Adapted Pull time: 500 ms");
    }

    @Test
    void theDefaultRigCalibratesAtTheAnalyseSpeedWithoutAClampLine() {
        program.onStart(control);

        assertThat(program.startup().rpm()).isEqualTo(RunParameters.toMotorRpm(50));
        assertThat(control.logLines()).noneMatch(line -> line.startsWith("Calibrating at"));
    }

    /** A rig slower than 50 mm/min calibrates at its maximum, says so once, and scales from it. */
    @Test
    void aRigSlowerThanTheAnalyseSpeedCalibratesAtItsMaximum() {
        program.teardown(control);
        TestParameter parameter = new TestParameter();
        parameter.speed = 20;
        parameter.upperTurnForce = 1.0;
        parameter.lowerTurnForce = 0.1;
        parameter.upperShutOffThreshold = 3.0;
        parameter.cycleCount = 10;
        parameter.startRampSeconds = 0.0;
        parameter.stopRampSeconds = 0.0;
        program = new TimeCyclicProgram(parameter, new RigLimits(150, 30), () -> now);
        control = new FakeRunControl(program.limits()).energized(program.startup());
        control.max = 1000f;
        control.min = 100f;

        assertThat(program.startup()).isEqualTo(
                new DriveSetup(RunParameters.toMotorRpm(30), Direction.RELEASE, new Ramp(3, 3)));
        assertThat(program.resumePolicy().resumeSetup(control)).isEqualTo(program.startup());
        program.onStart(control);
        assertThat(control.logLines()).filteredOn(line -> line.startsWith("Calibrating at"))
                .containsExactly("Calibrating at 30 mm/min, the rig's maximum speed, instead of 50 mm/min");

        measureBothTravelTimes();
        program.onLimit(Limit.LOWER, control);

        assertThat(program.phase()).isInstanceOf(TimedCyclePhase.class);
        // 1000 ms at the clamped 30 mm/min is 1500 ms at 20 mm/min.
        assertThat(control.logLines()).contains("Adapted Release time: 1500 ms", "Adapted Pull time: 1500 ms");
    }

    /**
     * The timed phase running before a turn schedules the opposite of it; the turn that handed over
     * schedules nothing.
     */
    @Test
    void theTimedPhaseSchedulesTheOppositeOfEveryTurnButTheOneThatHandedOver() throws InterruptedException {
        measureBothTravelTimes();
        program.onLimit(Limit.LOWER, control);
        assertThat(control.timedTurns.poll(300, TimeUnit.MILLISECONDS)).isNull();

        // A timed turn is handled as the crossing it stands for, and the phase answers it.
        program.onTimedTurn(Direction.RELEASE, control);

        assertThat(control.timedTurns.poll(5, TimeUnit.SECONDS)).isEqualTo(new RunEvent.TimedTurn(Direction.PULL));
    }

    /** H-15, I17: the hold entry closes the timed phase - nothing it scheduled fires - and recalibrates. */
    @Test
    void theHoldEntryClosesTheTimedPhaseAndRecalibrates() throws InterruptedException {
        measureBothTravelTimes();
        program.onLimit(Limit.LOWER, control);
        TimedCyclePhase timed = (TimedCyclePhase) program.phase();
        program.onTimedTurn(Direction.RELEASE, control);

        program.onHoldEntry();

        assertThat(timed.isClosed()).isTrue();
        assertThat(program.phase()).isInstanceOf(CalibrationPhase.class);
        // The pull scheduled 500 ms out never arrives.
        assertThat(control.timedTurns.poll(1, TimeUnit.SECONDS)).isNull();
    }

    /** H-15: a resume calibrates again from scratch, on the calibration setup, with nothing timing turns. */
    @Test
    void aResumeRestartsCalibrationOnTheCalibrationSetup() throws InterruptedException {
        measureBothTravelTimes();
        program.onLimit(Limit.LOWER, control);
        program.onHoldEntry();
        Phase held = program.phase();

        assertThat(program.resumePolicy().resumeSetup(control)).isEqualTo(program.startup());

        assertThat(program.phase()).isInstanceOf(CalibrationPhase.class).isNotSameAs(held);
        control.calls.clear();
        program.onLimit(Limit.UPPER, control);
        // A calibration turn, not a cyclic one, and nothing scheduled.
        assertThat(control.logLines()).contains("<b>init: start release round<b/>");
        assertThat(control.calls).noneMatch(c -> c.startsWith("runPhaseSpeed"));
        assertThat(control.timedTurns.poll(300, TimeUnit.MILLISECONDS)).isNull();
    }

    /** A timed turn that arrives during calibration came from a closed phase and moves nothing. */
    @Test
    void aTimedTurnDuringCalibrationIsDropped() {
        program.onLimit(Limit.UPPER, control);
        control.calls.clear();

        assertThat(program.onTimedTurn(Direction.PULL, control)).isEqualTo(Verdict.CONTINUE);

        assertThat(control.calls).isEmpty();
    }

    /** The cycle count is the program's, not the phase's: a recalibration does not restart it. */
    @Test
    void theCycleCountCarriesAcrossARecalibration() {
        measureBothTravelTimes();
        program.onLimit(Limit.LOWER, control);
        assertThat(control.logLines()).contains("CycleCount 10");

        program.onHoldEntry();
        program.resumePolicy().resumeSetup(control);
        measureBothTravelTimes();
        control.calls.clear();
        program.onLimit(Limit.LOWER, control);

        assertThat(control.logLines()).contains("CycleCount 9");
    }

    @Test
    void theTeardownResetsTheRampThenClosesThePhase() {
        measureBothTravelTimes();
        program.onLimit(Limit.LOWER, control);
        TimedCyclePhase timed = (TimedCyclePhase) program.phase();
        control.calls.clear();

        program.teardown(control);

        assertThat(control.calls).containsExactly("disableSecondRamp");
        assertThat(timed.isClosed()).isTrue();
    }
}
