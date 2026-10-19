package ch.rupfizupfi.deck.testrunner.program;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** I13: a non-finite limit is refused, at start and on every adaptation. Ported from {@code TestContextTest}. */
class LimitsTest {

    @ParameterizedTest
    @ValueSource(doubles = {Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY})
    void aNonFiniteUpperLimitIsRefused(double limit) {
        assertThatThrownBy(() -> Limits.withoutCeiling(limit, 0.0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("non-finite upper force limit");
    }

    @ParameterizedTest
    @ValueSource(doubles = {Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY})
    void aNonFiniteLowerLimitIsRefused(double limit) {
        assertThatThrownBy(() -> Limits.withoutCeiling(0.0, limit))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("non-finite lower force limit");
    }

    @ParameterizedTest
    @ValueSource(doubles = {Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY})
    void aCyclicCeilingMustBeFinite(double ceiling) {
        assertThatThrownBy(() -> Limits.withCeiling(100.0, -100.0, ceiling))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("non-finite ceiling force limit");
    }

    @ParameterizedTest
    @ValueSource(doubles = {Double.NaN, Double.NEGATIVE_INFINITY})
    void onlyPositiveInfinityMeansNoCeiling(double ceiling) {
        assertThatThrownBy(() -> new Limits(100.0, -100.0, ceiling))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(Limits.withoutCeiling(100.0, -100.0).ceiling()).isEqualTo(Double.POSITIVE_INFINITY);
    }

    @ParameterizedTest
    @ValueSource(doubles = {Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY})
    void anAdaptationToANonFiniteLimitIsRefusedAndLeavesTheOldLimits(double limit) {
        Limits limits = Limits.withCeiling(100.0, -100.0, 300.0);

        assertThatThrownBy(() -> limits.adapted(limit, -100.0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> limits.adapted(100.0, limit)).isInstanceOf(IllegalArgumentException.class);
        assertThat(limits).isEqualTo(new Limits(100.0, -100.0, 300.0));
    }

    @Test
    void anAdaptationKeepsTheCeiling() {
        assertThat(Limits.withCeiling(100.0, -100.0, 300.0).adapted(90.0, -80.0))
                .isEqualTo(new Limits(90.0, -80.0, 300.0));
    }

    @Test
    void theEnvelopeIsTheLargerLimitMagnitude() {
        assertThat(Limits.withoutCeiling(5000.0, -500.0).envelope()).isEqualTo(5000.0);
        assertThat(Limits.withoutCeiling(100.0, -7000.0).envelope()).isEqualTo(7000.0);
    }
}
