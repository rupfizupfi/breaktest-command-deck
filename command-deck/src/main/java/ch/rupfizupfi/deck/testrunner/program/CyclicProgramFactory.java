package ch.rupfizupfi.deck.testrunner.program;

import ch.rupfizupfi.deck.data.TestResult;
import ch.rupfizupfi.deck.data.TestType;
import ch.rupfizupfi.deck.rig.RigLimits;
import org.springframework.stereotype.Component;

@Component
final class CyclicProgramFactory implements TestProgramFactory {

    @Override
    public TestType type() {
        return TestType.CYCLIC;
    }

    @Override
    public TestProgram create(TestResult result, RigLimits rig) {
        return new CyclicProgram(result.testParameter);
    }
}
