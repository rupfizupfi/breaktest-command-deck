package ch.rupfizupfi.deck.rig;

import ch.rupfizupfi.deck.data.TestParameter;
import ch.rupfizupfi.deck.data.TestType;

/**
 * The one rule set a {@link TestParameter} must satisfy for the bench to run it. Checked twice:
 * when the cms saves the parameter, so a bad one is refused while the operator is still at the
 * form, and again by the deck at run start, because the rig limits may have changed since.
 * <p>
 * Forces are kN, speed is mm/min, ramp times are seconds. {@code upperShutOffThreshold} is the hard
 * trip for every type, so it must sit inside the rated capacity. A lower force (the destructive
 * shut-off, the cyclic turn) is never negative: the bench pulls, and zero is the floor (owner,
 * 2026-10-13).
 * <p>
 * A field is checked only for the types that use it: the destructive run reads neither the turn
 * forces, the cycle count nor the ramps, the cyclic types never read the lower shut-off threshold.
 */
public final class TestParameterRules {
    /**
     * The longest ramp time, in seconds, the drive takes: the CFW11's P0100-P0103 hold 0..9990
     * tenths of a second, and its driver refuses anything outside that before the write. The deck
     * converts by truncating seconds times ten ({@code Ramp#ofSeconds}), so this converts to 9990.
     */
    public static final double MAX_RAMP_SECONDS = 999.0;

    private TestParameterRules() {
    }

    /**
     * The save-side check. Every {@link TestType} runs, so a parameter with a type must pass
     * {@link #requireValid}; one without a type is stored as it is and refused at run start.
     *
     * @throws IllegalArgumentException naming the first parameter the bench could not run
     */
    public static void requireSavable(TestParameter parameter, RigLimits limits) {
        if (parameter != null && parameter.type != null) {
            requireValid(parameter, limits);
        }
    }

    /**
     * The run-start check.
     *
     * @throws IllegalArgumentException naming the first parameter the bench cannot run
     */
    public static void requireValid(TestParameter parameter, RigLimits limits) {
        if (limits == null) {
            throw new IllegalArgumentException("no rig limits to check the test parameter against");
        }
        if (parameter == null) {
            throw new IllegalArgumentException("the test result has no test parameter");
        }
        // First, so the operator is told what the parameter is before what is wrong with it.
        // A stored value outside the three never gets here: TestTypeConverter refuses it at load.
        if (parameter.type == null) {
            throw new IllegalArgumentException("unknown test parameter type: null");
        }
        if (parameter.speed <= 0) {
            throw new IllegalArgumentException("speed must be positive, was " + parameter.speed + " mm/min");
        }
        if (parameter.speed > limits.maxSpeedMillimetresPerMinute()) {
            throw new IllegalArgumentException("speed must be at most " + limits.maxSpeedMillimetresPerMinute()
                    + " mm/min, the rig's maximum crosshead speed; was " + parameter.speed + " mm/min");
        }

        double capacityKn = limits.ratedCapacityKilonewton();
        double upperShutOff = requireForce(parameter.upperShutOffThreshold, "upper shut-off threshold");
        if (upperShutOff <= 0 || upperShutOff > capacityKn) {
            throw new IllegalArgumentException("upper shut-off threshold must be within (0, " + capacityKn
                    + "] kN, the rig's rated capacity; was " + upperShutOff + " kN");
        }

        switch (parameter.type) {
            case DESTRUCTIVE -> {
                double lowerShutOff = requireForce(parameter.lowerShutOffThreshold, "lower shut-off threshold");
                requireNonNegative(lowerShutOff, "lower shut-off threshold");
                if (lowerShutOff >= upperShutOff) {
                    throw new IllegalArgumentException("lower shut-off threshold " + lowerShutOff
                            + " kN must be below the upper one, " + upperShutOff + " kN");
                }
            }
            case CYCLIC, TIME_CYCLIC -> {
                double upperTurn = requireForce(parameter.upperTurnForce, "upper turn force");
                double lowerTurn = requireForce(parameter.lowerTurnForce, "lower turn force");
                requireNonNegative(lowerTurn, "lower turn force");
                if (lowerTurn >= upperTurn) {
                    throw new IllegalArgumentException("lower turn force " + lowerTurn
                            + " kN must be below the upper turn force, " + upperTurn + " kN");
                }
                // The ceiling sits above the turn, or every cycle would end the run.
                if (upperTurn >= upperShutOff) {
                    throw new IllegalArgumentException("upper turn force " + upperTurn
                            + " kN must be below the upper shut-off threshold, " + upperShutOff + " kN");
                }
                // Zero would still finish after the first counted cycle; null is an NPE at setup.
                if (parameter.cycleCount == null || parameter.cycleCount < 1) {
                    throw new IllegalArgumentException("cycle count must be at least 1, was " + parameter.cycleCount);
                }
                requireRamp(parameter.startRampSeconds, "start ramp");
                requireRamp(parameter.stopRampSeconds, "stop ramp");
            }
        }
    }

    /**
     * Unset (null) or zero leaves the run on the drive's first ramp, as {@code Ramp#ofSeconds} reads
     * it. Anything else must convert to a time the drive takes, or the energize would throw mid-run.
     */
    private static void requireRamp(Double seconds, String name) {
        if (seconds == null) {
            return;
        }
        if (!Double.isFinite(seconds) || seconds < 0 || seconds > MAX_RAMP_SECONDS) {
            throw new IllegalArgumentException(name + " must be within [0, " + MAX_RAMP_SECONDS
                    + "] s, the drive's ramp range; was " + seconds + " s");
        }
    }

    private static void requireNonNegative(double force, String name) {
        if (force < 0) {
            throw new IllegalArgumentException(name + " must be at least 0 kN, lower thresholds are never"
                    + " negative; was " + force + " kN");
        }
    }

    private static double requireForce(Double value, String name) {
        if (value == null || !Double.isFinite(value)) {
            throw new IllegalArgumentException(name + " is required, was " + value);
        }
        return value;
    }
}
