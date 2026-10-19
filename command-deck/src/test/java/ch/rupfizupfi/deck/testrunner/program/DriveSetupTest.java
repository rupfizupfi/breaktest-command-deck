package ch.rupfizupfi.deck.testrunner.program;

import ch.rupfizupfi.deck.device.api.Drive;
import ch.rupfizupfi.deck.rig.TestParameterRules;
import ch.rupfizupfi.deck.testrunner.run.Direction;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.InOrder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

/**
 * The one energize sequence (OQ-64 step 7, owner decision D3): speed, ramp, then exactly one
 * control-word write carrying start, general enable, direction and the second-ramp bit together.
 */
class DriveSetupTest {

    private final Drive drive = mock(Drive.class);

    @ParameterizedTest
    @EnumSource(Direction.class)
    void withARampItIsSpeedThenRampThenOneControlWordWithTheRampBit(Direction direction) {
        new DriveSetup(800, direction, new Ramp(15, 20)).applyTo(drive);

        InOrder order = inOrder(drive);
        order.verify(drive).setSpeedReferenceValueAsRpm(800);
        // H-17: the ramp is configured before the start.
        order.verify(drive).setSecondSpeedRampTime(15, 20);
        order.verify(drive).setControlParameters(true, true, direction.isRelease(), null, true);
        verifyNoMoreInteractions(drive);
    }

    @ParameterizedTest
    @EnumSource(Direction.class)
    void withoutARampTheSecondRampIsSwitchedOffBeforeTheOneControlWord(Direction direction) {
        new DriveSetup(1333, direction, null).applyTo(drive);

        InOrder order = inOrder(drive);
        order.verify(drive).setSpeedReferenceValueAsRpm(1333);
        // A run without ramps must not inherit a previous run's second ramp.
        order.verify(drive).setUseSecondRamp(false);
        order.verify(drive).setControlParameters(true, true, direction.isRelease(), null, false);
        verifyNoMoreInteractions(drive);
    }

    /** D3: no separate enable, start or direction write is left for a lost frame to split. */
    @Test
    void theEnergizeNeverWritesStartEnableOrDirectionOnTheirOwn() {
        new DriveSetup(800, Direction.RELEASE, new Ramp(10, 10)).applyTo(drive);
        new DriveSetup(800, Direction.PULL, null).applyTo(drive);

        verify(drive, never()).setStart(anyBoolean());
        verify(drive, never()).setGeneralEnable(anyBoolean());
        verify(drive, never()).setDirection(anyBoolean());
        verify(drive, never()).setUseSecondRamp(true);
        verify(drive, times(2)).setControlParameters(any(), any(), any(), any(), any());
        verify(drive, times(1)).setSecondSpeedRampTime(anyInt(), anyInt());
    }

    /** The time-cyclic analyse phase's setup, byte for byte what TimeCyclicTest wrote before D3. */
    @Test
    void theTimeCyclicAnalyseSetupIsTheWriteItAlwaysWas() {
        new DriveSetup(133, Direction.RELEASE, new Ramp(3, 3)).applyTo(drive);

        InOrder order = inOrder(drive);
        order.verify(drive).setSpeedReferenceValueAsRpm(133);
        order.verify(drive).setSecondSpeedRampTime(3, 3);
        order.verify(drive).setControlParameters(true, true, true, null, true);
        verifyNoMoreInteractions(drive);
    }

    @Test
    void aSetupNeedsADirection() {
        assertThatThrownBy(() -> new DriveSetup(800, null, null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void rampsComeFromTheParameterOnlyWhenBothArePositive() {
        assertThat(Ramp.ofSeconds(1.5, 2.0)).isEqualTo(new Ramp(15, 20));
        assertThat(Ramp.ofSeconds(1.5, 0.0)).isNull();
        assertThat(Ramp.ofSeconds(0.0, 2.0)).isNull();
        assertThat(Ramp.ofSeconds(null, 2.0)).isNull();
        assertThat(Ramp.ofSeconds(null, null)).isNull();
    }

    /**
     * The longest ramp {@code TestParameterRules} admits converts to the drive's ceiling, 9990
     * tenths (CFW11 P0100-P0103; the driver refuses more), so a run the gate passed never has its
     * ramp write refused mid-run.
     */
    @Test
    void theLongestRampTheRulesAdmitIsTheDrivesCeiling() {
        double max = TestParameterRules.MAX_RAMP_SECONDS;
        assertThat(Ramp.ofSeconds(max, max)).isEqualTo(new Ramp(9990, 9990));
    }
}
