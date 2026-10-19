package ch.rupfizupfi.deck.device.api;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** What a plugin built against 1.4.0 gets from the 1.5.0 defaults. */
class DriveDefaultsTest {

    @Test
    void theStopFallbackIsTheOneWriteStopThenZeroSpeed() {
        var drive = new Recording14Drive();

        drive.stop();

        assertThat(drive.calls).containsExactly(
                "setControlParameters(false, false, null, null, null)",
                "setSpeedReferenceValueAsRpm(0)");
    }

    @Test
    void theStopFallbackSendsZeroSpeedEvenWhenTheControlWriteFails() {
        var drive = new Recording14Drive();
        drive.controlWriteFailure = new DriveCommunicationException("control word write failed");

        assertThatThrownBy(drive::stop).isInstanceOf(DriveCommunicationException.class)
                .hasMessageContaining("control word");
        assertThat(drive.calls).contains("setSpeedReferenceValueAsRpm(0)");
    }

    /**
     * A plugin built before 1.4.0 lets Kotlin's checked {@code NegativeConfirmationException} cross
     * {@link Drive} undeclared; it must neither skip the zero-speed write nor change type.
     */
    @Test
    void aCheckedFailureFromTheControlWriteStillSendsZeroSpeedAndIsRethrownAsItself() {
        var drive = new Recording14Drive();
        var checked = new Exception("negative confirmation");
        drive.controlWriteFailure = checked;

        assertThatThrownBy(drive::stop).isSameAs(checked);
        assertThat(drive.calls).containsExactly(
                "setControlParameters(false, false, null, null, null)",
                "setSpeedReferenceValueAsRpm(0)");
    }

    @Test
    void anErrorFromTheControlWriteStillSendsZeroSpeedAndIsRethrown() {
        var drive = new Recording14Drive();
        var error = new UnsatisfiedLinkError("vendor DLL");
        drive.controlWriteFailure = error;

        assertThatThrownBy(drive::stop).isSameAs(error);
        assertThat(drive.calls).contains("setSpeedReferenceValueAsRpm(0)");
    }

    @Test
    void whenBothWritesFailTheSecondIsSuppressedInTheFirst() {
        var drive = new Recording14Drive();
        var first = new Exception("control word");
        var second = new DriveCommunicationException("speed reference");
        drive.controlWriteFailure = first;
        drive.speedWriteFailure = second;

        assertThatThrownBy(drive::stop).isSameAs(first)
                .satisfies(t -> assertThat(t.getSuppressed()).containsExactly(second));
    }

    /** A driver that stores a terminal fault rethrows the same instance from both writes. */
    @Test
    void whenBothWritesThrowOneInstanceThatInstanceIsRethrown() {
        var drive = new Recording14Drive();
        var terminal = new DriveCommunicationException("link down");
        drive.controlWriteFailure = terminal;
        drive.speedWriteFailure = terminal;

        assertThatThrownBy(drive::stop).isSameAs(terminal)
                .satisfies(t -> assertThat(t.getSuppressed()).isEmpty());
        assertThat(drive.calls).containsExactly(
                "setControlParameters(false, false, null, null, null)",
                "setSpeedReferenceValueAsRpm(0)");
    }

    @Test
    void whenOnlyTheZeroSpeedWriteFailsItIsThrown() {
        var drive = new Recording14Drive();
        var second = new DriveCommunicationException("speed reference");
        drive.speedWriteFailure = second;

        assertThatThrownBy(drive::stop).isSameAs(second);
        assertThat(drive.calls).hasSize(2);
    }

    @Test
    void anOlderPluginAnswersCallerSerializes() {
        assertThat(new Recording14Drive().gating()).isEqualTo(DriveGating.CALLER_SERIALIZES);
    }

    @Test
    void whatNeedsTheGateFailsClosedNamingTheRebuild() {
        var drive = new Recording14Drive();

        assertThatThrownBy(drive::stopEpoch).isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("1.5.0").hasMessageContaining("Rebuild");
        assertThatThrownBy(() -> drive.runLinked(0, d -> d.setStart(true)))
                .isInstanceOf(UnsupportedOperationException.class).hasMessageContaining("Rebuild");
        assertThatThrownBy(drive::async).isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("Rebuild");
        assertThat(drive.calls).as("an unfenced group body never runs").isEmpty();
    }

    @Test
    void aDeadlineIsACommsFailureAndAStaleEpochIsNot() {
        assertThat(DriveCommunicationException.class).isAssignableFrom(DriveDeadlineException.class);
        assertThat(DriveCommunicationException.class.isAssignableFrom(StaleStopEpochException.class)).isFalse();
    }

    /** Implements exactly the methods a 1.4.0 provider had to. */
    private static final class Recording14Drive implements Drive {

        final List<String> calls = new ArrayList<>();
        Throwable controlWriteFailure;
        Throwable speedWriteFailure;

        @SuppressWarnings("unchecked")
        private static <E extends Throwable> void sneakyThrow(Throwable t) throws E {
            throw (E) t;
        }

        @Override
        public void setControlParameters(Boolean start, Boolean generalEnable, Boolean directionIsForward,
                                         Boolean localRemote, Boolean useSecondRamp) {
            calls.add("setControlParameters(" + start + ", " + generalEnable + ", " + directionIsForward
                    + ", " + localRemote + ", " + useSecondRamp + ")");
            if (controlWriteFailure != null) {
                Recording14Drive.<RuntimeException>sneakyThrow(controlWriteFailure);
            }
        }

        @Override
        public Map<String, Boolean> getControlParameters() {
            return Map.of();
        }

        @Override
        public void setStart(boolean start) {
            calls.add("setStart(" + start + ")");
        }

        @Override
        public void setGeneralEnable(boolean generalEnable) {
            calls.add("setGeneralEnable(" + generalEnable + ")");
        }

        @Override
        public void setDirection(boolean directionIsForward) {
        }

        @Override
        public boolean getDirection() {
            return false;
        }

        @Override
        public void setUseSecondRamp(boolean useSecondRamp) {
        }

        @Override
        public void setSecondSpeedRampTime(int accelerationRampTime, int decelerationRampTime) {
        }

        @Override
        public void setSpeedReferenceValueAsRpm(int rpm) {
            calls.add("setSpeedReferenceValueAsRpm(" + rpm + ")");
            if (speedWriteFailure != null) {
                Recording14Drive.<RuntimeException>sneakyThrow(speedWriteFailure);
            }
        }

        @Override
        public int getMotorSpeedValueAsRpm() {
            return 0;
        }

        @Override
        public Map<String, Integer> getMotorData() {
            return Map.of();
        }

        @Override
        public void setActionInCaseOfCommunicationError(int action) {
        }

        @Override
        public void close() {
        }
    }
}
