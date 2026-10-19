package ch.rupfizupfi.deck.testrunner;

import ch.rupfizupfi.deck.data.TestParameter;
import ch.rupfizupfi.deck.data.TestType;
import ch.rupfizupfi.deck.rig.RigLimits;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The rule set itself is {@code TestParameterRulesTest} in cms; this pins the run-start delegation. */
class RunParametersTest {

    @Test
    void everyTypeConvertsSpeedTheSameWay() {
        // OQ-63: TimeCyclicTest divided by 375 and commanded 0 rpm for its 50 mm/min analyse run.
        assertThat(RunParameters.toMotorRpm(50)).isEqualTo(133);
        assertThat(RunParameters.toMotorRpm(0.375)).isEqualTo(1);
    }

    /**
     * The CFW11 driver writes P0683 as rpm x 8192 / synchronous speed into a signed 16-bit register,
     * refusing anything past 32767 — so the ceiling is just under 4 x the synchronous speed, which
     * the driver derives at open from the drive's P0402/P0403 nameplate (120 x f / poles). The
     * fastest speed any rig-limit setting admits must convert inside that for every motor the bench
     * could carry, or a parameter the gate passed would fail at the drive's first speed write.
     * <p>
     * The slowest synchronous speed is the binding one. 2667 rpm needs at least 667 rpm, so it fits
     * every 2- to 8-pole motor at 50 or 60 Hz (8-pole 50 Hz: 750 rpm, ceiling 2999 rpm); a 10- or
     * 12-pole 50 Hz motor (600, 500 rpm) would refuse it at energize, before the start bit.
     */
    @Test
    void theFastestSpeedTheRigLimitsAdmitStaysInsideTheDrivesSpeedReference() {
        int rpm = RunParameters.toMotorRpm(RigLimits.SPEED_UPPER_BOUND_MILLIMETRES_PER_MINUTE);
        assertThat(rpm).isEqualTo(2667);
        for (int frequencyHz : new int[]{50, 60}) {
            for (int poles = 2; poles <= 8; poles += 2) {
                int synchronousRpm = 120 * frequencyHz / poles;
                assertThat(Math.round((double) rpm * 8192 / synchronousRpm))
                        .as("%d-pole %d Hz, synchronous %d rpm", poles, frequencyHz, synchronousRpm)
                        .isLessThanOrEqualTo(Short.MAX_VALUE);
            }
        }
    }

    @Test
    void aValidCyclicParameterPassesOnTheDefaultRig() {
        assertThatCode(() -> RunParameters.requireRunnable(cyclic(80.0, 10), RigLimits.DEFAULTS))
                .doesNotThrowAnyException();
    }

    @Test
    void theRunsOwnRigLimitsDecideNotTheDefaults() {
        RigLimits smallRig = new RigLimits(60, 20);

        assertThatThrownBy(() -> RunParameters.requireRunnable(cyclic(80.0, 10), smallRig))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("rated capacity");
        assertThatThrownBy(() -> RunParameters.requireRunnable(cyclic(55.0, 30), smallRig))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maximum crosshead speed");
    }

    @Test
    void noRigLimitsIsARefusalNotAnNpe() {
        assertThatThrownBy(() -> RunParameters.requireRunnable(cyclic(80.0, 10), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("rig limits");
    }

    private static TestParameter cyclic(Double upperShutOff, int speed) {
        TestParameter parameter = new TestParameter();
        parameter.type = TestType.CYCLIC;
        parameter.speed = speed;
        parameter.lowerTurnForce = 10.0;
        parameter.upperTurnForce = 50.0;
        parameter.upperShutOffThreshold = upperShutOff;
        parameter.cycleCount = 1;
        return parameter;
    }
}
