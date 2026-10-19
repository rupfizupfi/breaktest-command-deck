package ch.rupfizupfi.deck.testrunner.program;

import ch.rupfizupfi.deck.data.SettingRepository;
import ch.rupfizupfi.deck.data.TestParameter;
import ch.rupfizupfi.deck.data.TestResult;
import ch.rupfizupfi.deck.data.TestType;
import ch.rupfizupfi.deck.rig.RigLimits;
import ch.rupfizupfi.deck.testrunner.TestRunnerFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/**
 * The registry is the gate for "a new type without a program": the context refuses to start
 * rather than the first run of that type failing. {@code ApplicationContextTest} resolves the real
 * one; here, each refusal names what is wrong.
 */
class TestProgramRegistryTest {

    private static List<TestProgramFactory> allFactories() {
        return new ArrayList<>(List.of(
                new DestructiveProgramFactory(mock(SettingRepository.class), mock(TestRunnerFactory.class)),
                new CyclicProgramFactory(),
                new TimeCyclicProgramFactory()));
    }

    @ParameterizedTest
    @EnumSource(TestType.class)
    void aMissingFactoryFailsConstructionNamingTheType(TestType missing) {
        List<TestProgramFactory> factories = allFactories();
        factories.removeIf(factory -> factory.type() == missing);

        assertThatThrownBy(() -> new TestProgramRegistry(factories))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no program factory for test type " + missing);
    }

    @Test
    void aSecondFactoryForATypeFailsConstructionNamingBoth() {
        List<TestProgramFactory> factories = allFactories();
        factories.add(new CyclicProgramFactory());

        assertThatThrownBy(() -> new TestProgramRegistry(factories))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("two program factories for test type CYCLIC")
                .hasMessageContaining(CyclicProgramFactory.class.getName());
    }

    @Test
    void aFactoryNamingNoTypeIsRefused() {
        List<TestProgramFactory> factories = allFactories();
        factories.add(new TestProgramFactory() {
            @Override
            public TestType type() {
                return null;
            }

            @Override
            public TestProgram create(TestResult result, RigLimits rig) {
                throw new AssertionError("never created");
            }
        });

        assertThatThrownBy(() -> new TestProgramRegistry(factories))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("names no test type");
    }

    @ParameterizedTest
    @EnumSource(TestType.class)
    void eachTypeGetsItsOwnProgramAFreshOnePerRun(TestType type) {
        TestProgramRegistry registry = new TestProgramRegistry(allFactories());
        TestResult result = result(type);

        TestProgram first = registry.create(result, RigLimits.DEFAULTS);
        TestProgram second = registry.create(result, RigLimits.DEFAULTS);

        Class<?> expected = switch (type) {
            case DESTRUCTIVE -> DestructiveProgram.class;
            case CYCLIC -> CyclicProgram.class;
            case TIME_CYCLIC -> TimeCyclicProgram.class;
        };
        assertThat(first).isInstanceOf(expected);
        assertThat(second).isNotSameAs(first);
    }

    @Test
    void aParameterWithoutATypeIsRefused() {
        TestProgramRegistry registry = new TestProgramRegistry(allFactories());

        assertThatThrownBy(() -> registry.create(result(null), RigLimits.DEFAULTS))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("names no test type");
    }

    private static TestResult result(TestType type) {
        TestParameter parameter = new TestParameter();
        parameter.type = type;
        parameter.speed = 100;
        parameter.upperShutOffThreshold = 3.0;
        parameter.lowerShutOffThreshold = 1.0;
        parameter.upperTurnForce = 2.2;
        parameter.lowerTurnForce = 2.0;
        parameter.cycleCount = 2;
        TestResult result = new TestResult();
        result.setId(9L);
        result.testParameter = parameter;
        return result;
    }
}
