package ch.rupfizupfi.deck.testrunner.program;

import ch.rupfizupfi.deck.testrunner.run.Direction;
import ch.rupfizupfi.deck.testrunner.run.RunControl;
import jakarta.annotation.Nullable;

/**
 * The cyclic resume: the run's own speed and ramp, heading toward the nearer limit. That stands in
 * for the direction the run was heading, which nothing recorded before the drive was de-energized.
 *
 * @param ramp null keeps the second ramp off, as {@link DriveSetup} has it
 */
record TowardNearerLimit(int rpm, @Nullable Ramp ramp) implements ResumePolicy {

    @Override
    public boolean supportsResume() {
        return true;
    }

    @Override
    public DriveSetup resumeSetup(RunControl control) {
        return new DriveSetup(rpm, direction(control), ramp);
    }

    /** An unknown force unloads rather than adds load. */
    static Direction direction(RunControl control) {
        float force = control.lastForce();
        if (!Float.isFinite(force)) {
            return Direction.RELEASE;
        }
        Limits limits = control.limits();
        boolean nearerToUpper = Math.abs(limits.upper() - force) < Math.abs(force - limits.lower());
        // Pulling loads toward the upper limit, releasing unloads toward the lower.
        return nearerToUpper ? Direction.PULL : Direction.RELEASE;
    }
}
