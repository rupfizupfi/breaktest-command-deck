package ch.rupfizupfi.deck.testrunner.program;

import ch.rupfizupfi.deck.data.TestResult;
import ch.rupfizupfi.deck.data.TestType;
import ch.rupfizupfi.deck.rig.RigLimits;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * {@link TestType} to its program, replacing the reflective {@code TestRunnerFactory#createTestRunner}
 * and the switch in {@code TestRunnerThread} (OQ-64 step 7, A-04, A-09). The constructor refuses a
 * context in which any type lacks exactly one {@link TestProgramFactory}, so a new type without a
 * program fails startup - the context tests are that gate - instead of failing its first run.
 */
@Component
public final class TestProgramRegistry {
    private final Map<TestType, TestProgramFactory> factories = new EnumMap<>(TestType.class);

    public TestProgramRegistry(List<TestProgramFactory> factories) {
        for (TestProgramFactory factory : factories) {
            TestType type = Objects.requireNonNull(factory.type(),
                    () -> factory.getClass().getName() + " names no test type");
            TestProgramFactory previous = this.factories.putIfAbsent(type, factory);
            if (previous != null) {
                throw new IllegalStateException("two program factories for test type " + type + ": "
                        + previous.getClass().getName() + " and " + factory.getClass().getName());
            }
        }
        for (TestType type : TestType.values()) {
            if (!this.factories.containsKey(type)) {
                throw new IllegalStateException("no program factory for test type " + type
                        + " - a TestType needs exactly one TestProgramFactory bean");
            }
        }
    }

    /**
     * @throws IllegalArgumentException when the result's parameter names no type; the run gate
     *                                  ({@code RunParameters#requireRunnable}) refuses that first
     */
    public TestProgram create(TestResult result, RigLimits rig) {
        TestType type = result.testParameter == null ? null : result.testParameter.type;
        if (type == null) {
            throw new IllegalArgumentException("the test parameter names no test type");
        }
        return factories.get(type).create(result, rig);
    }
}
