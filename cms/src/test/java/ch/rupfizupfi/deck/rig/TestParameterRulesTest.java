package ch.rupfizupfi.deck.rig;

import ch.rupfizupfi.deck.data.TestParameter;
import ch.rupfizupfi.deck.data.TestType;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The rule set both the cms save and the deck's run start apply. */
class TestParameterRulesTest {

    private static final RigLimits RIG = RigLimits.DEFAULTS;

    @Test
    void aValidParameterOfEveryTypePasses() {
        assertThatCode(() -> TestParameterRules.requireValid(cyclic(10.0, 50.0, 80.0), RIG)).doesNotThrowAnyException();
        TestParameter timeCyclic = cyclic(10.0, 50.0, 80.0);
        timeCyclic.type = TestType.TIME_CYCLIC;
        assertThatCode(() -> TestParameterRules.requireValid(timeCyclic, RIG)).doesNotThrowAnyException();
        assertThatCode(() -> TestParameterRules.requireValid(destructive(50.0, 1.0), RIG)).doesNotThrowAnyException();
    }

    @Test
    void aCyclicRunNeedsAShutOffAboveItsUpperTurnForce() {
        assertThatThrownBy(() -> TestParameterRules.requireValid(cyclic(10.0, 50.0, null), RIG))
                .hasMessageContaining("upper shut-off threshold is required");
        assertThatThrownBy(() -> TestParameterRules.requireValid(cyclic(10.0, 50.0, 50.0), RIG))
                .hasMessageContaining("must be below the upper shut-off threshold");
    }

    @Test
    void theShutOffMayNotExceedTheRatedCapacityButMayEqualIt() {
        assertThatThrownBy(() -> TestParameterRules.requireValid(cyclic(10.0, 50.0, 151.0), RIG))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("rated capacity");
        assertThatCode(() -> TestParameterRules.requireValid(cyclic(10.0, 50.0, 150.0), RIG)).doesNotThrowAnyException();
        assertThatThrownBy(() -> TestParameterRules.requireValid(destructive(0.0, -1.0), RIG))
                .hasMessageContaining("upper shut-off threshold must be within");
    }

    @Test
    void theCapacityComesFromTheRigLimitsNotAConstant() {
        assertThatThrownBy(() -> TestParameterRules.requireValid(cyclic(10.0, 50.0, 80.0), new RigLimits(60, 500)))
                .hasMessageContaining("(0, 60.0] kN");
    }

    @Test
    void theSpeedMayNotExceedTheRigsMaximum() {
        TestParameter parameter = cyclic(10.0, 50.0, 80.0);
        parameter.speed = 501;
        assertThatThrownBy(() -> TestParameterRules.requireValid(parameter, RIG))
                .hasMessageContaining("maximum crosshead speed");
        parameter.speed = 500;
        assertThatCode(() -> TestParameterRules.requireValid(parameter, RIG)).doesNotThrowAnyException();
    }

    @Test
    void turnForcesMustBeOrdered() {
        assertThatThrownBy(() -> TestParameterRules.requireValid(cyclic(50.0, 10.0, 80.0), RIG))
                .hasMessageContaining("lower turn force");
    }

    @Test
    void aZeroSpeedIsRefusedBecauseTheRunWouldNeverEnd() {
        TestParameter parameter = cyclic(10.0, 50.0, 80.0);
        parameter.speed = 0;
        assertThatThrownBy(() -> TestParameterRules.requireValid(parameter, RIG)).hasMessageContaining("speed must be positive");
    }

    @Test
    void destructiveShutOffsMustBeOrdered() {
        assertThatThrownBy(() -> TestParameterRules.requireValid(destructive(50.0, 60.0), RIG))
                .hasMessageContaining("lower shut-off");
        assertThatThrownBy(() -> TestParameterRules.requireValid(destructive(50.0, null), RIG))
                .hasMessageContaining("lower shut-off threshold is required");
    }

    @Test
    void aNonFiniteForceCountsAsMissing() {
        assertThatThrownBy(() -> TestParameterRules.requireValid(cyclic(10.0, 50.0, Double.NaN), RIG))
                .hasMessageContaining("upper shut-off threshold is required");
    }

    @Test
    void aMissingTypeIsNamedNotAnNpe() {
        assertThatThrownBy(() -> TestParameterRules.requireValid(new TestParameter(), RIG))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unknown test parameter type");
    }

    @Test
    void missingInputsAreRefusalsNotNpes() {
        assertThatThrownBy(() -> TestParameterRules.requireValid(null, RIG))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no test parameter");
        assertThatThrownBy(() -> TestParameterRules.requireValid(cyclic(10.0, 50.0, 80.0), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no rig limits");
    }

    @Test
    void aSaveKeepsAnUntypedParameterStorableButHoldsEveryTypedOneToTheRunRules() {
        TestParameter untyped = new TestParameter();
        untyped.upperShutOffThreshold = 900.0;
        assertThatCode(() -> TestParameterRules.requireSavable(untyped, RIG)).doesNotThrowAnyException();

        assertThatThrownBy(() -> TestParameterRules.requireSavable(cyclic(10.0, 50.0, 151.0), RIG))
                .hasMessageContaining("rated capacity");
        TestParameter timeCyclic = cyclic(10.0, 50.0, 151.0);
        timeCyclic.type = TestType.TIME_CYCLIC;
        assertThatThrownBy(() -> TestParameterRules.requireSavable(timeCyclic, RIG))
                .hasMessageContaining("rated capacity");
        assertThatThrownBy(() -> TestParameterRules.requireSavable(destructive(151.0, 1.0), RIG))
                .hasMessageContaining("rated capacity");
    }

    @Test
    void aCyclicRunNeedsAtLeastOneCycle() {
        for (TestType type : new TestType[]{TestType.CYCLIC, TestType.TIME_CYCLIC}) {
            TestParameter parameter = cyclic(10.0, 50.0, 80.0);
            parameter.type = type;
            parameter.cycleCount = null;
            assertThatThrownBy(() -> TestParameterRules.requireValid(parameter, RIG))
                    .as(type.name()).hasMessageContaining("cycle count must be at least 1, was null");
            parameter.cycleCount = 0;
            assertThatThrownBy(() -> TestParameterRules.requireValid(parameter, RIG))
                    .as(type.name()).hasMessageContaining("cycle count must be at least 1, was 0");
            parameter.cycleCount = -4;
            assertThatThrownBy(() -> TestParameterRules.requireValid(parameter, RIG))
                    .as(type.name()).hasMessageContaining("cycle count");
            parameter.cycleCount = 1;
            assertThatCode(() -> TestParameterRules.requireValid(parameter, RIG)).as(type.name()).doesNotThrowAnyException();
        }
    }

    @Test
    void aDestructiveRunIgnoresTheFieldsItNeverReads() {
        TestParameter parameter = destructive(50.0, 1.0);
        parameter.cycleCount = 0;
        parameter.startRampSeconds = -1.0;
        parameter.stopRampSeconds = Double.NaN;
        assertThatCode(() -> TestParameterRules.requireValid(parameter, RIG)).doesNotThrowAnyException();
    }

    @Test
    void unsetOrZeroRampsMeanTheFirstRamp() {
        TestParameter parameter = cyclic(10.0, 50.0, 80.0);
        assertThatCode(() -> TestParameterRules.requireValid(parameter, RIG)).doesNotThrowAnyException();
        parameter.startRampSeconds = 0.0;
        parameter.stopRampSeconds = 0.0;
        assertThatCode(() -> TestParameterRules.requireValid(parameter, RIG)).doesNotThrowAnyException();
    }

    @Test
    void rampsMustFitTheDrivesRange() {
        TestParameter parameter = cyclic(10.0, 50.0, 80.0);
        parameter.startRampSeconds = TestParameterRules.MAX_RAMP_SECONDS;
        parameter.stopRampSeconds = 0.5;
        assertThatCode(() -> TestParameterRules.requireValid(parameter, RIG)).doesNotThrowAnyException();

        parameter.startRampSeconds = 999.1;
        assertThatThrownBy(() -> TestParameterRules.requireValid(parameter, RIG))
                .hasMessageContaining("start ramp must be within [0, 999.0] s");
        parameter.startRampSeconds = 2.0;
        parameter.stopRampSeconds = -0.1;
        assertThatThrownBy(() -> TestParameterRules.requireValid(parameter, RIG))
                .hasMessageContaining("stop ramp must be within");
    }

    @Test
    void aNonFiniteRampIsRefused() {
        for (double bad : new double[]{Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY}) {
            TestParameter parameter = cyclic(10.0, 50.0, 80.0);
            parameter.type = TestType.TIME_CYCLIC;
            parameter.stopRampSeconds = bad;
            assertThatThrownBy(() -> TestParameterRules.requireValid(parameter, RIG))
                    .as("stop ramp " + bad).hasMessageContaining("stop ramp must be within");
        }
    }

    @Test
    void aLowerForceIsNeverNegativeButMayBeZero() {
        for (TestType type : new TestType[]{TestType.CYCLIC, TestType.TIME_CYCLIC}) {
            TestParameter negative = cyclic(-0.1, 50.0, 80.0);
            negative.type = type;
            assertThatThrownBy(() -> TestParameterRules.requireValid(negative, RIG))
                    .as(type.name()).hasMessageContaining("lower turn force must be at least 0 kN");
            TestParameter zero = cyclic(0.0, 50.0, 80.0);
            zero.type = type;
            assertThatCode(() -> TestParameterRules.requireValid(zero, RIG)).as(type.name()).doesNotThrowAnyException();
        }
        assertThatThrownBy(() -> TestParameterRules.requireValid(destructive(50.0, -0.1), RIG))
                .hasMessageContaining("lower shut-off threshold must be at least 0 kN");
        assertThatCode(() -> TestParameterRules.requireValid(destructive(50.0, 0.0), RIG)).doesNotThrowAnyException();
        assertThatThrownBy(() -> TestParameterRules.requireSavable(destructive(50.0, -150.0), RIG))
                .hasMessageContaining("lower shut-off threshold must be at least 0 kN");
    }

    @Test
    void aNegativeSpeedIsRefused() {
        TestParameter parameter = destructive(50.0, 1.0);
        parameter.speed = -10;
        assertThatThrownBy(() -> TestParameterRules.requireValid(parameter, RIG)).hasMessageContaining("speed must be positive");
    }

    @Test
    void aMissingOrNonFiniteTurnForceIsNamed() {
        assertThatThrownBy(() -> TestParameterRules.requireValid(cyclic(null, 50.0, 80.0), RIG))
                .hasMessageContaining("lower turn force is required");
        assertThatThrownBy(() -> TestParameterRules.requireValid(cyclic(10.0, Double.POSITIVE_INFINITY, 80.0), RIG))
                .hasMessageContaining("upper turn force is required");
        assertThatThrownBy(() -> TestParameterRules.requireValid(destructive(50.0, Double.NaN), RIG))
                .hasMessageContaining("lower shut-off threshold is required");
    }

    static TestParameter cyclic(Double lowerTurn, Double upperTurn, Double upperShutOff) {
        TestParameter parameter = new TestParameter();
        parameter.type = TestType.CYCLIC;
        parameter.speed = 10;
        parameter.lowerTurnForce = lowerTurn;
        parameter.upperTurnForce = upperTurn;
        parameter.upperShutOffThreshold = upperShutOff;
        parameter.cycleCount = 3;
        return parameter;
    }

    static TestParameter destructive(Double upperShutOff, Double lowerShutOff) {
        TestParameter parameter = new TestParameter();
        parameter.type = TestType.DESTRUCTIVE;
        parameter.speed = 10;
        parameter.upperShutOffThreshold = upperShutOff;
        parameter.lowerShutOffThreshold = lowerShutOff;
        return parameter;
    }
}
