package ch.rupfizupfi.deck.testrunner.startup.check;

import ch.rupfizupfi.deck.device.api.Drive;
import ch.rupfizupfi.deck.device.api.DriveProvider;
import ch.rupfizupfi.deck.device.frequencyinverter.FrequencyInverterDevice;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FrequencyInverterCheckTest {

    private FrequencyInverterDevice device;

    @AfterEach
    void releaseRemainingReference() {
        if (device != null && device.isDriveHandleOpen()) {
            device.disconnect();
        }
    }

    @Test
    void aDriveThatCannotBeOpenedNamesTheFailureAndLeavesNoHandleBehind() {
        DriveProvider refusing = () -> {
            throw new IllegalStateException("inverter is not plugged in");
        };
        device = new FrequencyInverterDevice(refusing);

        assertThatThrownBy(() -> new FrequencyInverterCheck(device).execute())
                .isInstanceOf(CheckFailedException.class)
                .hasMessageContaining("IllegalStateException")
                .hasMessageContaining("inverter is not plugged in");
        assertThat(device.isDriveHandleOpen()).isFalse();
    }

    @Test
    void anAnsweringDrivePassesAndTheCheckBalancesItsOwnConnect() {
        var drive = new FakeDrive(null);
        device = new FrequencyInverterDevice(() -> drive);

        assertThatCode(() -> new FrequencyInverterCheck(device).execute()).doesNotThrowAnyException();

        assertThat(drive.controlParameterReads()).isEqualTo(1);
        assertThat(drive.closeCalls()).isEqualTo(1);
        assertThat(device.isDriveHandleOpen()).isFalse();
    }

    @Test
    void aDriveThatDoesNotAnswerTheReadIsReportedAndReleased() {
        var drive = new FakeDrive(new IllegalStateException("drive is not answering"));
        device = new FrequencyInverterDevice(() -> drive);

        assertThatThrownBy(() -> new FrequencyInverterCheck(device).execute())
                .isInstanceOf(CheckFailedException.class)
                .hasMessageContaining("not answering")
                .hasMessageContaining("IllegalStateException");
        assertThat(device.isDriveHandleOpen()).isFalse();
    }

    @Test
    void aDeviceAnotherHolderKeepsOpenStaysOpenAfterTheCheck() {
        var drive = new FakeDrive(null);
        device = new FrequencyInverterDevice(() -> drive);
        device.connect();

        assertThatCode(() -> new FrequencyInverterCheck(device).execute()).doesNotThrowAnyException();

        assertThat(device.isDriveHandleOpen()).isTrue();
        assertThat(drive.closeCalls()).isZero();
    }

    /** Answers every read, or fails {@code getControlParameters} with the given throwable. */
    private static final class FakeDrive implements Drive {
        private final Throwable readFailure;
        private final AtomicInteger controlParameterReads = new AtomicInteger();
        private final AtomicInteger closeCalls = new AtomicInteger();

        private FakeDrive(Throwable readFailure) {
            this.readFailure = readFailure;
        }

        int controlParameterReads() {
            return controlParameterReads.get();
        }

        int closeCalls() {
            return closeCalls.get();
        }

        @Override
        public Map<String, Boolean> getControlParameters() {
            controlParameterReads.incrementAndGet();
            if (readFailure != null) {
                throw sneaky(readFailure);
            }
            return Map.of("start", false, "generalEnable", false, "useSecondRamp", false,
                    "directionIsForward", true);
        }

        @Override
        public Map<String, Integer> getMotorData() {
            return Map.of("speed", 0, "current", 0, "voltage", 400, "torque", 0);
        }

        @Override
        public void setControlParameters(Boolean start, Boolean generalEnable, Boolean directionIsForward,
                                         Boolean localRemote, Boolean useSecondRamp) {
        }

        @Override
        public void setStart(boolean start) {
        }

        @Override
        public void setGeneralEnable(boolean generalEnable) {
        }

        @Override
        public void setDirection(boolean directionIsForward) {
        }

        @Override
        public boolean getDirection() {
            return true;
        }

        @Override
        public void setUseSecondRamp(boolean useSecondRamp) {
        }

        @Override
        public void setSecondSpeedRampTime(int accelerationRampTime, int decelerationRampTime) {
        }

        @Override
        public void setSpeedReferenceValueAsRpm(int rpm) {
        }

        @Override
        public int getMotorSpeedValueAsRpm() {
            return 0;
        }

        @Override
        public void setActionInCaseOfCommunicationError(int action) {
        }

        @Override
        public void close() {
            closeCalls.incrementAndGet();
        }
    }

    /** Throws a checked exception from a method that declares none, as the Kotlin driver does. */
    @SuppressWarnings("unchecked")
    private static <T extends Throwable> RuntimeException sneaky(Throwable t) throws T {
        throw (T) t;
    }
}
