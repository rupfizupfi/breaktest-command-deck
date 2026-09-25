package ch.rupfizupfi.deck.device.frequencyinverter;

import ch.rupfizupfi.deck.device.api.Drive;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FrequencyInverterDeviceTest {

    private static final int FAILING_CALLS = 2;
    /** Long enough for the 400 ms poll cadence to get past {@link #FAILING_CALLS} failures. */
    private static final long INFO_TIMEOUT_SECONDS = 3;

    @Test
    @Timeout(20)
    void checkedExceptionFromTheDriveLeavesThePollLoopRunning() throws InterruptedException {
        // The driver's comms failure is a checked exception crossing Drive undeclared; a
        // hand-written fake is required because Mockito refuses to throw it here.
        assertPollingSurvives(new Exception("Send Not OK"));
    }

    @Test
    @Timeout(20)
    void runtimeExceptionFromTheDriveLeavesThePollLoopRunning() throws InterruptedException {
        assertPollingSurvives(new IllegalStateException("drive is confused"));
    }

    private void assertPollingSurvives(Throwable failure) throws InterruptedException {
        var drive = new FailingThenHealthyDrive(failure, FAILING_CALLS);
        var device = new FrequencyInverterDevice(() -> drive);
        var receivedInfo = new CountDownLatch(1);
        InfoObserver observer = info -> receivedInfo.countDown();

        device.connect();
        try {
            device.registerObserver(observer);
            try {
                assertThat(receivedInfo.await(INFO_TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
                assertThat(drive.motorDataCalls()).isGreaterThan(FAILING_CALLS);
            } finally {
                device.unregisterObserver(observer);
            }
        } finally {
            device.disconnect();
        }
        assertThat(drive.isClosed()).isTrue();
    }

    @Test
    void toInfoNamesTheMissingKey() {
        var motorData = new HashMap<String, Integer>(
                Map.of("speed", 1200, "current", 3, "voltage", 400, "torque", 12));
        var controlParameters = Map.of("start", true, "generalEnable", true, "useSecondRamp", false,
                "directionIsForward", true);

        var info = FrequencyInverterDevice.toInfo(motorData, controlParameters, 7);
        assertThat(info.id).isEqualTo(7);
        assertThat(info.speed).isEqualTo(1200);
        assertThat(info.motorCurrent).isEqualTo(3);
        assertThat(info.motorVoltage).isEqualTo(400);
        assertThat(info.motorTorque).isEqualTo(12);
        assertThat(info.start).isTrue();
        assertThat(info.generalEnable).isTrue();
        assertThat(info.useSecondRamp).isFalse();
        assertThat(info.directionIsForward).isTrue();

        motorData.remove("torque");
        assertThatThrownBy(() -> FrequencyInverterDevice.toInfo(motorData, controlParameters, 8))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("torque");
    }

    @Test
    @Timeout(20)
    void aThrowingObserverDoesNotStopTheOthersOrThePoll() throws InterruptedException {
        var drive = healthyDrive();
        var device = new FrequencyInverterDevice(() -> drive);
        var receivedInfos = new CountDownLatch(2);
        InfoObserver throwing = info -> {
            throw new IllegalStateException("observer is broken");
        };
        InfoObserver counting = info -> receivedInfos.countDown();

        device.connect();
        try {
            device.registerObserver(throwing);
            device.registerObserver(counting);
            try {
                assertThat(receivedInfos.await(INFO_TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
                assertThat(drive.motorDataCalls()).isGreaterThanOrEqualTo(2);
            } finally {
                device.unregisterObserver(counting);
                device.unregisterObserver(throwing);
            }
        } finally {
            device.disconnect();
        }
    }

    @Test
    @Timeout(20)
    void frameIdsAreStrictlyIncreasing() throws InterruptedException {
        var drive = healthyDrive();
        var device = new FrequencyInverterDevice(() -> drive);
        List<Integer> ids = new CopyOnWriteArrayList<>();
        var receivedInfos = new CountDownLatch(3);
        InfoObserver observer = info -> {
            ids.add(info.id);
            receivedInfos.countDown();
        };

        device.connect();
        try {
            device.registerObserver(observer);
            try {
                assertThat(receivedInfos.await(INFO_TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
            } finally {
                device.unregisterObserver(observer);
            }
        } finally {
            device.disconnect();
        }

        assertThat(ids).hasSizeGreaterThanOrEqualTo(3).doesNotHaveDuplicates().isSorted();
    }

    @Test
    @Timeout(20)
    void anAbandonedPollThreadExitsWhenItsReplacementStartsAndPublishesNothing() throws InterruptedException {
        var drive = new BlockingFirstReadDrive();
        var device = new FrequencyInverterDevice(() -> drive);
        InfoObserver blocking = info -> {
        };

        device.connect();
        try {
            device.registerObserver(blocking);
            var abandoned = drive.awaitFirstCaller();
            // The join is bounded, so this returns with the blocked thread still inside the drive.
            device.unregisterObserver(blocking);

            List<Thread> deliveringThreads = new CopyOnWriteArrayList<>();
            var receivedInfos = new CountDownLatch(2);
            InfoObserver counting = info -> {
                deliveringThreads.add(Thread.currentThread());
                receivedInfos.countDown();
            };
            device.registerObserver(counting);
            try {
                drive.release();
                assertThat(receivedInfos.await(INFO_TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();

                abandoned.join(3000);
                assertThat(abandoned.isAlive()).isFalse();
                assertThat(deliveringThreads).isNotEmpty().doesNotContain(abandoned);
            } finally {
                device.unregisterObserver(counting);
            }
        } finally {
            device.disconnect();
        }
        assertThat(drive.isClosed()).isTrue();
    }

    @Test
    @Timeout(20)
    void aThrowingCloseStillReleasesTheHandle() {
        var drive = new UncloseableDrive();
        var opens = new AtomicInteger();
        var device = new FrequencyInverterDevice(() -> {
            opens.incrementAndGet();
            return drive;
        });

        device.connect();
        assertThat(device.isDriveHandleOpen()).isTrue();

        assertThatCode(device::disconnect).doesNotThrowAnyException();
        assertThat(device.isDriveHandleOpen()).isFalse();

        // The next connect() opens the only live handle instead of stacking one over a dead one.
        device.connect();
        assertThat(opens).hasValue(2);
        device.disconnect();
    }

    private static FailingThenHealthyDrive healthyDrive() {
        return new FailingThenHealthyDrive(new IllegalStateException("unused"), 0);
    }

    /** Throws the given failure on the first {@code failingCalls} reads, then answers normally. */
    private static final class FailingThenHealthyDrive extends StubDrive {
        private final Throwable failure;
        private final int failingCalls;
        private final AtomicInteger motorDataCalls = new AtomicInteger();

        private FailingThenHealthyDrive(Throwable failure, int failingCalls) {
            this.failure = failure;
            this.failingCalls = failingCalls;
        }

        int motorDataCalls() {
            return motorDataCalls.get();
        }

        @Override
        public Map<String, Integer> getMotorData() {
            if (motorDataCalls.incrementAndGet() <= failingCalls) {
                throw sneaky(failure);
            }
            return super.getMotorData();
        }
    }

    /**
     * Blocks the first read until {@link #release()} and swallows the interrupt, as a vendor call
     * that catches {@code InterruptedException} does. Records the calling thread of every read.
     */
    private static final class BlockingFirstReadDrive extends StubDrive {
        private final CountDownLatch released = new CountDownLatch(1);
        private final CountDownLatch firstReadStarted = new CountDownLatch(1);
        private final List<Thread> callers = new CopyOnWriteArrayList<>();
        private final AtomicBoolean firstRead = new AtomicBoolean(true);

        @Override
        public Map<String, Integer> getMotorData() {
            callers.add(Thread.currentThread());
            if (firstRead.compareAndSet(true, false)) {
                firstReadStarted.countDown();
                awaitRelease();
            }
            return super.getMotorData();
        }

        @Override
        public Map<String, Boolean> getControlParameters() {
            callers.add(Thread.currentThread());
            return super.getControlParameters();
        }

        private void awaitRelease() {
            while (true) {
                try {
                    released.await();
                    return;
                } catch (InterruptedException e) {
                    // Dropped on purpose: the interrupt must not end this call.
                }
            }
        }

        /** The thread that entered the blocking read. */
        Thread awaitFirstCaller() throws InterruptedException {
            assertThat(firstReadStarted.await(INFO_TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
            return callers.getFirst();
        }

        void release() {
            released.countDown();
        }
    }

    /** Fails every close, as a wedged or unplugged drive does. */
    private static final class UncloseableDrive extends StubDrive {
        @Override
        public void close() {
            throw new IllegalStateException("drive handle refuses to close");
        }
    }

    /** Answers every read with the full key set; subclasses add the behaviour under test. */
    private static class StubDrive implements Drive {
        private volatile boolean closed = false;

        boolean isClosed() {
            return closed;
        }

        @Override
        public Map<String, Integer> getMotorData() {
            return Map.of("speed", 1200, "current", 3, "voltage", 400, "torque", 12);
        }

        @Override
        public Map<String, Boolean> getControlParameters() {
            return Map.of("start", true, "generalEnable", true, "useSecondRamp", false,
                    "directionIsForward", true);
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
            return 1200;
        }

        @Override
        public void setActionInCaseOfCommunicationError(int action) {
        }

        @Override
        public void close() {
            closed = true;
        }
    }

    /** Throws a checked exception from a method that declares none, as the Kotlin driver does. */
    @SuppressWarnings("unchecked")
    private static <T extends Throwable> RuntimeException sneaky(Throwable t) throws T {
        throw (T) t;
    }
}
