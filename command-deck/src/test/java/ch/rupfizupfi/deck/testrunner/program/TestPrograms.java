package ch.rupfizupfi.deck.testrunner.program;

import ch.rupfizupfi.deck.data.TestParameter;
import ch.rupfizupfi.deck.rig.RigLimits;

import java.util.function.LongSupplier;

/** The package-private programs, for the loop tests in other packages. Test sources only. */
public final class TestPrograms {
    private TestPrograms() {
    }

    /** A time-cyclic program on the default rig whose calibration reads {@code clock} for its travel times. */
    public static TestProgram timeCyclic(TestParameter parameter, LongSupplier clock) {
        return new TimeCyclicProgram(parameter, RigLimits.DEFAULTS, clock);
    }
}
