package ch.rupfizupfi.deck.testrunner.program;

import ch.rupfizupfi.deck.device.api.Drive;
import ch.rupfizupfi.deck.testrunner.run.Direction;
import jakarta.annotation.Nullable;

import java.util.Objects;

/**
 * How a run energizes the drive: the energize sequence, written once for every test type (OQ-64
 * step 7). {@code MotorSafetyController#energize} wraps {@link #applyTo} - the latch check and the
 * stop-epoch fence, the link-loss arming before it in the same linked group - so none of that is a
 * program's concern.
 *
 * @param rpm       the speed reference, already converted by {@code RunParameters#toMotorRpm}
 * @param direction the direction the motor starts in
 * @param ramp      the second speed ramp, or null to run on the first one
 */
public record DriveSetup(int rpm, Direction direction, @Nullable Ramp ramp) {

    public DriveSetup {
        Objects.requireNonNull(direction, "direction");
    }

    /**
     * Speed, then the ramp, then <b>one</b> control-word write carrying start, general enable,
     * direction and the second-ramp bit together (owner decision D3, the same pattern as the
     * verified one-write stop). A lost frame therefore leaves the drive fully de-energized, never
     * half-enabled on the wrong direction or ramp.
     * <p>
     * The ramp is configured before the start (H-17), so the motor never accelerates on the wrong
     * ramp. Without one the second ramp is also switched off on its own first, as every type did
     * before D3: a run without ramps must not inherit a previous run's second ramp when that run's
     * cleanup reset failed (a stop past tier 1 leaves no handle to reset it on).
     */
    public void applyTo(Drive drive) {
        drive.setSpeedReferenceValueAsRpm(rpm);
        if (ramp != null) {
            drive.setSecondSpeedRampTime(ramp.accelerationTenths(), ramp.decelerationTenths());
        } else {
            drive.setUseSecondRamp(false);
        }
        // start, general enable, direction, local/remote untouched, second ramp.
        drive.setControlParameters(true, true, direction.isRelease(), null, ramp != null);
    }
}
