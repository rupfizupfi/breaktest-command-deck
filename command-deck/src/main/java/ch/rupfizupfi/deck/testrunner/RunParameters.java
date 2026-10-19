package ch.rupfizupfi.deck.testrunner;

import ch.rupfizupfi.deck.data.TestParameter;
import ch.rupfizupfi.deck.rig.RigLimits;
import ch.rupfizupfi.deck.rig.TestParameterRules;

/**
 * The run-start gate for a test parameter: everything here is checked before the first device is
 * touched, so a parameter the bench cannot honour fails the run instead of driving the motor.
 * <p>
 * The rules are {@link TestParameterRules}, shared with the cms save, and are checked again here
 * because the rig limits may have changed since the parameter was saved.
 */
public final class RunParameters {
    /** Motor revolutions per mm of crosshead travel are 1 / this; the gearbox pitch in mm/rev. */
    static final double MM_PER_MOTOR_REVOLUTION = 0.375;

    private RunParameters() {
    }

    /** mm/min to the drive's rpm setpoint. The one conversion every test type uses (OQ-63). */
    public static int toMotorRpm(double millimetresPerMinute) {
        return (int) Math.round(millimetresPerMinute / MM_PER_MOTOR_REVOLUTION);
    }

    /**
     * @param limits the run's snapshot of the rig limits
     * @throws IllegalArgumentException naming the first parameter the bench cannot run
     */
    public static void requireRunnable(TestParameter parameter, RigLimits limits) {
        TestParameterRules.requireValid(parameter, limits);
    }
}
