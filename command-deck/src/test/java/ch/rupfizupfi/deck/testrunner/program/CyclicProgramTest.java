package ch.rupfizupfi.deck.testrunner.program;

import ch.rupfizupfi.deck.data.TestParameter;
import ch.rupfizupfi.deck.testrunner.run.Direction;
import ch.rupfizupfi.deck.testrunner.run.FinishCause;
import ch.rupfizupfi.deck.testrunner.run.Limit;
import ch.rupfizupfi.deck.testrunner.run.Verdict;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The cyclic program against a fake {@link ch.rupfizupfi.deck.testrunner.run.RunControl}: its
 * declaration, the reversal ordering (H-05), the limit adaptation, the cycle count, the resume
 * direction and the teardown. Pure: no threads, no drive. Ported from {@code CyclicTestTest}; the
 * latched-turn refusal moved with {@code turn} into {@code RunLoopTest}.
 */
class CyclicProgramTest {

    private TestParameter parameter;
    private CyclicProgram program;
    private FakeRunControl control;

    @BeforeEach
    void createProgram() {
        parameter = new TestParameter();
        parameter.speed = 50;
        parameter.upperTurnForce = 1.0;
        parameter.lowerTurnForce = 0.1;
        parameter.upperShutOffThreshold = 3.0;
        parameter.cycleCount = 10;
        parameter.startRampSeconds = 0.0;
        parameter.stopRampSeconds = 0.0;
        program = new CyclicProgram(parameter);
        control = new FakeRunControl(program.limits()).energized(program.startup());
    }

    @Test
    void itTurnsBetweenTheTurnForcesUnderTheShutOffCeiling() {
        assertThat(program.limits()).isEqualTo(new Limits(1000.0, 100.0, 3000.0));
        assertThat(program.name()).isEqualTo("CyclicTest");
        assertThat(program.resumePolicy().supportsResume()).isTrue();
    }

    @Test
    void itStartsReleasingAtTheRunSpeedOnTheFirstRampWithoutRamps() {
        // 50 mm/min / 0.375 mm/rev (OQ-63).
        assertThat(program.startup()).isEqualTo(new DriveSetup(133, Direction.RELEASE, null));
    }

    @Test
    void itStartsOnTheSecondRampWhenBothRampsAreSet() {
        parameter.startRampSeconds = 1.5;
        parameter.stopRampSeconds = 2.0;

        assertThat(new CyclicProgram(parameter).startup())
                .isEqualTo(new DriveSetup(133, Direction.RELEASE, new Ramp(15, 20)));
    }

    @Test
    void itAnnouncesItsForcesAndCycles() {
        program.onStart(control);

        assertThat(control.logLines()).containsExactly("upperTurnForce 1000.0 Newton",
                "lowerTurnForce 100.0 Newton", "upperShutOffThreshold 3000.0 Newton", "CycleCount 10",
                "cyclic test start");
    }

    @Test
    void theUpperTurnReversesBeforeAnyLogLineAndAdaptsTheLowerLimitFromTheSnapshot() {
        control.direction = Direction.PULL;
        control.min = 120f;

        assertThat(program.onLimit(Limit.UPPER, control)).isEqualTo(Verdict.CONTINUE);

        // Snapshot, reverse, reset, then the bookkeeping: nothing ahead of the write but the read.
        assertThat(control.calls).startsWith("envelope", "turn RELEASE", "resetEnvelopeMin 1000.0");
        assertThat(control.calls.indexOf("turn RELEASE")).isLessThan(firstLog());
        // 100 + (100 - 120), floored at the target.
        assertThat(control.limits().lower()).isEqualTo(100.0);
        assertThat(control.logLines()).containsExactly("change direction to forward", "Current min value 120.0",
                "New lower limit 100.0", "CycleCount 10");
    }

    @Test
    void theLowerTurnReversesBeforeAnyLogLineAndCountsTheCycle() {
        control.max = 980f;

        assertThat(program.onLimit(Limit.LOWER, control)).isEqualTo(Verdict.CONTINUE);

        assertThat(control.calls).startsWith("envelope", "turn PULL", "resetEnvelopeMax 100.0");
        assertThat(control.calls.indexOf("turn PULL")).isLessThan(firstLog());
        assertThat(program.cycleCount()).isEqualTo(9);
        // 1000 + (1000 - 980), capped at the target.
        assertThat(control.limits().upper()).isEqualTo(1000.0);
        assertThat(control.logLines()).containsExactly("change direction to backword", "Current max value 980.0",
                "New upper limit 1000.0", "CycleCount 10");
    }

    @Test
    void anUnderShotLowerTurnRaisesTheLowerLimitTowardTheTarget() {
        control.direction = Direction.PULL;
        control.min = 80f;

        program.onLimit(Limit.UPPER, control);

        // 100 + (100 - 80): the next turn comes earlier, so the force lands on the target.
        assertThat(control.limits().lower()).isEqualTo(120.0);
        assertThat(control.limits().ceiling()).isEqualTo(3000.0);
    }

    @Test
    void theLastCycleIsAFinishVerdictAndEveryOtherTurnContinues() {
        parameter.cycleCount = 2;
        program = new CyclicProgram(parameter);
        control = new FakeRunControl(program.limits()).energized(program.startup());

        assertThat(program.onLimit(Limit.LOWER, control)).isEqualTo(Verdict.CONTINUE);
        assertThat(program.onLimit(Limit.UPPER, control)).isEqualTo(Verdict.CONTINUE);
        assertThat(program.onLimit(Limit.LOWER, control)).isEqualTo(Verdict.finish(FinishCause.CYCLES_DONE));
    }

    @Test
    void aTimedTurnReversesExactlyAsTheCrossingItStandsFor() {
        control.direction = Direction.PULL;

        assertThat(program.onTimedTurn(Direction.RELEASE, control)).isEqualTo(Verdict.CONTINUE);

        assertThat(control.calls).startsWith("envelope", "turn RELEASE", "resetEnvelopeMin 1000.0");
    }

    @Test
    void aRepeatedCrossingInTheCommandedDirectionDoesNotReverseAgain() {
        control.direction = Direction.PULL;
        program.onLimit(Limit.UPPER, control);
        int after = control.calls.size();

        program.onLimit(Limit.UPPER, control);

        assertThat(control.calls).hasSize(after);
    }

    @Test
    void aFailedReversalLeavesTheCommandedDirectionSoTheNextCrossingRetries() {
        control.direction = Direction.PULL;
        control.turnFailure = new IllegalStateException("drive did not answer");

        assertThatThrownBy(() -> program.onLimit(Limit.UPPER, control)).hasMessage("drive did not answer");
        assertThat(control.direction()).isEqualTo(Direction.PULL);

        program.onLimit(Limit.UPPER, control);
        assertThat(control.direction()).isEqualTo(Direction.RELEASE);
    }

    @Test
    void aRefusedReversalStillLeavesTheCachedDirection() {
        control.direction = Direction.PULL;
        control.latched = true;

        program.onLimit(Limit.UPPER, control);

        assertThat(control.direction()).isEqualTo(Direction.PULL);
    }

    @Test
    void aResumeHeadsTowardTheNearerLimit() {
        control.lastForce = 900f;
        assertThat(program.resumePolicy().resumeSetup(control)).isEqualTo(new DriveSetup(133, Direction.PULL, null));

        control.lastForce = 200f;
        assertThat(program.resumePolicy().resumeSetup(control)).isEqualTo(new DriveSetup(133, Direction.RELEASE, null));
    }

    @Test
    void aResumeOnAnUnknownForceUnloads() {
        control.lastForce = Float.NaN;

        assertThat(program.resumePolicy().resumeSetup(control).direction()).isEqualTo(Direction.RELEASE);
    }

    @Test
    void theTeardownSwitchesTheSecondRampOffAndOnlyLogsAFailure() {
        program.teardown(control);
        assertThat(control.calls).containsExactly("disableSecondRamp");

        control.rampFailure = new IllegalStateException("handle closed");
        program.teardown(control);
        assertThat(control.logLines()).containsExactly("could not reset second ramp: handle closed");
    }

    private int firstLog() {
        for (int i = 0; i < control.calls.size(); i++) {
            if (control.calls.get(i).startsWith("log ")) {
                return i;
            }
        }
        return Integer.MAX_VALUE;
    }
}
