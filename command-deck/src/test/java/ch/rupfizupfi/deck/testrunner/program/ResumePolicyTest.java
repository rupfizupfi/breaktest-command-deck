package ch.rupfizupfi.deck.testrunner.program;

import ch.rupfizupfi.deck.data.TestParameter;
import ch.rupfizupfi.deck.testrunner.run.Direction;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** How each type comes back from a hold (OQ-64 step 8): destructive refuses, cyclic heads for the nearer limit, time-cyclic recalibrates. */
class ResumePolicyTest {

    private static final Limits LIMITS = Limits.withCeiling(1000, 100, 3000);

    private final FakeRunControl control = new FakeRunControl(LIMITS);

    @Test
    void abortOnlyRefusesAndHasNoSetup() {
        assertThat(ResumePolicy.ABORT_ONLY.supportsResume()).isFalse();
        assertThatThrownBy(() -> ResumePolicy.ABORT_ONLY.resumeSetup(control))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("abort-only");
    }

    @Test
    void destructiveIsAbortOnly() {
        TestParameter parameter = new TestParameter();
        parameter.upperShutOffThreshold = 3.0;
        parameter.lowerShutOffThreshold = 1.0;
        DestructiveProgram program = new DestructiveProgram(parameter, 500, null, seconds -> { });

        assertThat(program.resumePolicy()).isSameAs(ResumePolicy.ABORT_ONLY);
    }

    @Test
    void cyclicPicksTheNearerLimitOnItsOwnSpeedAndRamp() {
        Ramp ramp = new Ramp(10, 10);
        ResumePolicy policy = new TowardNearerLimit(133, ramp);
        assertThat(policy.supportsResume()).isTrue();

        control.lastForce = 900f;
        assertThat(policy.resumeSetup(control)).isEqualTo(new DriveSetup(133, Direction.PULL, ramp));

        control.lastForce = 200f;
        assertThat(policy.resumeSetup(control)).isEqualTo(new DriveSetup(133, Direction.RELEASE, ramp));
    }

    @Test
    void anUnknownForceReleases() {
        ResumePolicy policy = new TowardNearerLimit(133, null);

        control.lastForce = Float.NaN;
        assertThat(policy.resumeSetup(control).direction()).isEqualTo(Direction.RELEASE);

        control.lastForce = Float.POSITIVE_INFINITY;
        assertThat(policy.resumeSetup(control).direction()).isEqualTo(Direction.RELEASE);
    }

    @Test
    void restartCalibrationRestartsThenAnswersTheCalibrationSetup() {
        AtomicInteger restarts = new AtomicInteger();
        DriveSetup calibration = new DriveSetup(133, Direction.RELEASE, new Ramp(3, 3));
        ResumePolicy policy = new RestartCalibration(restarts::incrementAndGet, calibration);

        assertThat(policy.supportsResume()).isTrue();
        assertThat(restarts).hasValue(0);
        assertThat(policy.resumeSetup(control)).isEqualTo(calibration);
        assertThat(restarts).hasValue(1);
    }
}
