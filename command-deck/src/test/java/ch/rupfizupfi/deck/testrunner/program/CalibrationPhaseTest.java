package ch.rupfizupfi.deck.testrunner.program;

import ch.rupfizupfi.deck.data.TestParameter;
import ch.rupfizupfi.deck.rig.RigLimits;
import ch.rupfizupfi.deck.testrunner.run.Direction;
import ch.rupfizupfi.deck.testrunner.run.Limit;
import ch.rupfizupfi.deck.testrunner.run.Verdict;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The time-cyclic calibration on its own: which edges start and end a leg's time, the scaling to
 * the run speed, and the handover only once both legs are timed. Pure: a fake clock, no threads.
 */
class CalibrationPhaseTest {

    private static final int RUN_SPEED = 25;
    private static final Ramp RUN_RAMP = new Ramp(20, 20);

    private CyclicTurns turns;
    private FakeRunControl control;
    private long now = 10_000;
    private Phase phase;

    @BeforeEach
    void createPhase() {
        TestParameter parameter = new TestParameter();
        parameter.speed = RUN_SPEED;
        parameter.upperTurnForce = 1.0;
        parameter.lowerTurnForce = 0.1;
        parameter.upperShutOffThreshold = 3.0;
        parameter.cycleCount = 1;
        turns = new CyclicTurns(parameter);
        control = new FakeRunControl(turns.initialLimits()).energized(
                new DriveSetup(133, Direction.RELEASE, new Ramp(3, 3)));
        reached();
        phase = new CalibrationPhase(turns, CalibrationPhase.ANALYSE_SPEED, RUN_SPEED, 66, RUN_RAMP, () -> now);
    }

    @AfterEach
    void closePhase() {
        phase.close();
    }

    /** The envelope came within the threshold of both turn forces. */
    private void reached() {
        control.max = 1000f - CalibrationPhase.FORCE_THRESHOLD + 1;
        control.min = 100f + CalibrationPhase.FORCE_THRESHOLD - 1;
    }

    private void at(long millis, Limit limit) {
        now = millis;
        phase = phase.onLimit(limit, control);
    }

    @Test
    void everyCalibrationTurnReversesFirstThenResetsTheEnvelopeAndLogs() {
        at(10_000, Limit.UPPER);

        assertThat(control.calls).containsExactly("envelope", "turn RELEASE", "resetEnvelopeMin 1000.0",
                "log <b>init: start release round<b/>", "log set start time for release",
                "log Current min value 399.0");
        assertThat(phase.verdict()).isEqualTo(Verdict.CONTINUE);
    }

    @Test
    void aTurnWhoseEnvelopeMissedItsForceStartsNoTime() {
        control.max = 1000f - CalibrationPhase.FORCE_THRESHOLD - 1;

        at(10_000, Limit.UPPER);

        assertThat(control.logLines()).doesNotContain("set start time for release");
    }

    /**
     * A release starts the release leg and ends the pull leg; a pull the other way round. Each leg
     * keeps its first start, so the release leg runs from the first release to the first pull and
     * the pull leg from that pull to the next release.
     */
    @Test
    void theLegsAreTimedOnTheirEdgesAndScaledToTheRunSpeed() {
        at(10_000, Limit.UPPER);
        at(12_000, Limit.LOWER);
        at(15_000, Limit.UPPER);
        assertThat(phase).isInstanceOf(CalibrationPhase.class);

        control.calls.clear();
        at(16_000, Limit.LOWER);

        assertThat(phase).isInstanceOf(TimedCyclePhase.class);
        // Measured at 50 mm/min, run at 25: twice as long.
        assertThat(control.logLines()).containsSubsequence("Release time: 2000 ms", "Pull time: 3000 ms",
                "Adapted Release time: 4000 ms", "Adapted Pull time: 6000 ms");
    }

    /** A rig slower than the analyse speed calibrates at its maximum, and the times scale from that. */
    @Test
    void legsMeasuredAtAClampedCalibrationSpeedScaleFromThatSpeed() {
        phase.close();
        phase = new CalibrationPhase(turns, 30, RUN_SPEED, 66, RUN_RAMP, () -> now);
        at(10_000, Limit.UPPER);
        at(12_000, Limit.LOWER);
        at(15_000, Limit.UPPER);
        at(16_000, Limit.LOWER);

        assertThat(phase).isInstanceOf(TimedCyclePhase.class);
        // Measured at 30 mm/min, run at 25: 6/5 as long.
        assertThat(control.logLines()).containsSubsequence("Release time: 2000 ms", "Pull time: 3000 ms",
                "Adapted Release time: 2400 ms", "Adapted Pull time: 3600 ms");
    }

    @Test
    void theCalibrationSpeedIsTheAnalyseSpeedClampedToTheRigMaximum() {
        assertThat(CalibrationPhase.calibrationSpeed(new RigLimits(150, 30))).isEqualTo(30.0);
        assertThat(CalibrationPhase.calibrationSpeed(new RigLimits(150, 50))).isEqualTo(50.0);
        assertThat(CalibrationPhase.calibrationSpeed(RigLimits.DEFAULTS)).isEqualTo(50.0);
    }

    @Test
    void itHandsOverOnlyOnceBothLegsAreTimed() {
        at(10_000, Limit.UPPER);
        at(12_000, Limit.LOWER);
        // The release leg is timed, the pull leg only started: this turn is still calibration.
        control.calls.clear();
        at(15_000, Limit.UPPER);

        assertThat(phase).isInstanceOf(CalibrationPhase.class);
        assertThat(control.calls).noneMatch(c -> c.startsWith("runPhaseSpeed"));
        assertThat(control.logLines()).noneMatch(l -> l.startsWith("Release time"));
    }

    @Test
    void untimedTurnsNeverHandOver() {
        control.max = 0f;
        control.min = 0f;
        for (int i = 0; i < 6; i++) {
            at(10_000 + i * 1000L, i % 2 == 0 ? Limit.UPPER : Limit.LOWER);
        }

        assertThat(phase).isInstanceOf(CalibrationPhase.class);
    }

    /**
     * The handing-over turn is the run's cyclic turn: the reversal, the run speed and ramp, then the
     * analysis lines; its verdict is the cyclic one, here the last cycle.
     */
    @Test
    void theHandoverTurnIsTheCyclicTurnThenTheRunSpeedThenTheAnalysis() {
        at(10_000, Limit.UPPER);
        at(12_000, Limit.LOWER);
        at(15_000, Limit.UPPER);
        control.calls.clear();

        at(16_000, Limit.LOWER);

        int reversal = control.calls.indexOf("turn PULL");
        int speed = control.calls.indexOf("runPhaseSpeed 66 " + RUN_RAMP);
        int cycleLine = control.calls.indexOf("log CycleCount 1");
        int analysis = control.calls.indexOf("log Release time: 2000 ms");
        assertThat(reversal).isNotNegative().isLessThan(cycleLine);
        assertThat(cycleLine).isLessThan(speed);
        assertThat(speed).isLessThan(analysis);
        assertThat(phase.verdict()).isEqualTo(Verdict.finish(ch.rupfizupfi.deck.testrunner.run.FinishCause.CYCLES_DONE));
    }

    @Test
    void aTimedTurnDuringCalibrationMovesNothing() {
        Phase next = phase.onTimedTurn(Direction.PULL, control);

        assertThat(next).isSameAs(phase);
        assertThat(control.calls).isEmpty();
        assertThat(next.verdict()).isEqualTo(Verdict.CONTINUE);
    }
}
