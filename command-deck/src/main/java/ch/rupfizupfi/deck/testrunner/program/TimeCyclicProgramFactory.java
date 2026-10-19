package ch.rupfizupfi.deck.testrunner.program;

import ch.rupfizupfi.deck.data.TestResult;
import ch.rupfizupfi.deck.data.TestType;
import ch.rupfizupfi.deck.rig.RigLimits;
import org.springframework.stereotype.Component;

@Component
final class TimeCyclicProgramFactory implements TestProgramFactory {

    @Override
    public TestType type() {
        return TestType.TIME_CYCLIC;
    }

    @Override
    public TestProgram create(TestResult result, RigLimits rig) {
        return new TimeCyclicProgram(result.testParameter, rig);
    }
}
