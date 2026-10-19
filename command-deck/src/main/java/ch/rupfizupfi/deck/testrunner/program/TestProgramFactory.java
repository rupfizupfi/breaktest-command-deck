package ch.rupfizupfi.deck.testrunner.program;

import ch.rupfizupfi.deck.data.TestResult;
import ch.rupfizupfi.deck.data.TestType;
import ch.rupfizupfi.deck.rig.RigLimits;

/**
 * Creates one {@link TestType}'s program for a run. A Spring bean per type; the
 * {@link TestProgramRegistry} refuses a context in which a type has none, or more than one.
 */
public interface TestProgramFactory {

    TestType type();

    /**
     * A new program for this run; never shared between runs.
     *
     * @param rig the rig limits the run snapshotted at its start, so a settings edit mid-run moves nothing
     */
    TestProgram create(TestResult result, RigLimits rig);
}
