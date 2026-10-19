package ch.rupfizupfi.deck.testrunner.program;

import jakarta.annotation.Nullable;

/**
 * The drive's second speed ramp, both times in tenths of a second as
 * {@code Drive#setSecondSpeedRampTime} takes them.
 */
public record Ramp(int accelerationTenths, int decelerationTenths) {

    /** Tenths per second; the drive's ramp resolution. */
    private static final int TENTHS_PER_SECOND = 10;

    /**
     * The test parameter's ramps, or null when either is unset or not positive: a run without
     * both ramps runs on the drive's first ramp, and the setup turns the second one off.
     */
    public static @Nullable Ramp ofSeconds(@Nullable Double startRampSeconds, @Nullable Double stopRampSeconds) {
        double start = startRampSeconds == null ? 0 : startRampSeconds;
        double stop = stopRampSeconds == null ? 0 : stopRampSeconds;
        if (start > 0 && stop > 0) {
            return new Ramp((int) (start * TENTHS_PER_SECOND), (int) (stop * TENTHS_PER_SECOND));
        }
        return null;
    }
}
