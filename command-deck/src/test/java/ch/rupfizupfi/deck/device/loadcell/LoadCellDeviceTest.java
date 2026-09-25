package ch.rupfizupfi.deck.device.loadcell;

import ch.rupfizupfi.deck.device.api.LoadCellStream;
import ch.rupfizupfi.deck.device.api.Measurement;
import ch.rupfizupfi.deck.device.api.StreamFailure;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Freshness, failure reporting and teardown of {@link LoadCellDevice} against a scripted stream.
 * <p>
 * A reader stranded by the bounded join reports the stream it holds rather than the field's; that
 * path needs a reader wedged in a native call, so it is covered by review of
 * {@code describeFailure(mine)} instead of by a case here.
 */
class LoadCellDeviceTest {

    /** The reader ticks every 20 ms; every wait here is a generous multiple of that. */
    private static final long DELIVERY_TIMEOUT_MS = 5000;

    private final ScriptedStream stream = new ScriptedStream();
    private final LoadCellDevice device = new LoadCellDevice(() -> stream);

    @AfterEach
    void releaseTheDevice() {
        // Unconditional: the zero clamp makes a second call a no-op, and no reader may outlive a test.
        device.disconnect();
    }

    @Test
    @Timeout(20)
    void aDeviceThatWasNeverOpenedHasNoFreshData() {
        assertThat(device.isDataFlowing(250)).isFalse();
        assertThat(device.awaitFreshMeasurement(100)).isFalse();
    }

    @Test
    @Timeout(20)
    void aDeliveredBatchMakesTheDeviceFresh() {
        device.connect();
        stream.enqueue(new Measurement(12.5f, System.currentTimeMillis()));

        assertThat(device.awaitFreshMeasurement(DELIVERY_TIMEOUT_MS)).isTrue();
        assertThat(device.isDataFlowing(250)).isTrue();
    }

    @Test
    @Timeout(20)
    void freshnessIsMeasuredFromTheCallAndNotFromTheLastBatch() {
        device.connect();
        stream.enqueue(new Measurement(1f, System.currentTimeMillis()));
        assertThat(device.awaitFreshMeasurement(DELIVERY_TIMEOUT_MS)).isTrue();

        assertThat(device.awaitFreshMeasurement(150)).isFalse();
    }

    @Test
    @Timeout(20)
    void registeredObserversReceiveTheDrainedMeasurements() throws InterruptedException {
        var first = new Measurement(1f, 100L);
        var second = new Measurement(2f, 120L);
        var observer = new RecordingObserver(2);
        device.registerObserver(observer);
        device.connect();
        stream.enqueue(first, second);

        assertThat(observer.awaitMeasurements(DELIVERY_TIMEOUT_MS)).isTrue();
        assertThat(observer.received()).containsExactly(first, second);
    }

    @Test
    @Timeout(20)
    void aFailureCarryingADriverCodeIsReportedVerbatim() {
        device.connect();
        stream.fail(new StreamFailure("A12", "IOException", "cable unplugged"));

        assertThat(device.getStreamFailure()).contains("A12").contains("cable unplugged");
    }

    @Test
    @Timeout(20)
    void aStreamThatStoppedWithoutAnErrorIsReportedAsSuch() {
        device.connect();
        stream.fail(null);

        assertThat(device.getStreamFailure()).isEqualTo("load cell reader thread stopped without reporting an error");
    }

    @Test
    @Timeout(20)
    void aReadingStreamReportsNoFailure() {
        device.connect();

        assertThat(device.getStreamFailure()).isNull();
    }

    @Test
    @Timeout(20)
    void discardedReadingsAreReportedWhileTheStreamKeepsDelivering() throws InterruptedException {
        var observer = new RecordingObserver(1);
        device.registerObserver(observer);
        device.connect();
        stream.setDroppedSampleCount(7);
        stream.enqueue(new Measurement(3f, 300L));

        assertThat(observer.awaitMeasurements(DELIVERY_TIMEOUT_MS)).isTrue();
        assertThat(device.getDroppedSampleCount()).isEqualTo(7);
        assertThat(device.getStreamFailure()).isNull();
    }

    @Test
    @Timeout(20)
    void aCheckedExceptionFromTheDrainLeavesTheReaderRunning() throws InterruptedException {
        // The driver's checked failure crosses LoadCellStream undeclared; a hand-written fake is
        // required because Mockito refuses to throw it here.
        var delivered = new Measurement(9f, 900L);
        var observer = new RecordingObserver(1);
        device.registerObserver(observer);
        stream.failOnNextDrains(new IOException("usb frame"), 2);
        device.connect();
        stream.enqueue(delivered);

        assertThat(observer.awaitMeasurements(DELIVERY_TIMEOUT_MS)).isTrue();
        assertThat(observer.received()).containsExactly(delivered);
    }

    @Test
    @Timeout(20)
    void closingTheDeviceFlushesEachObserverOnce() {
        var observer = new RecordingObserver(0);
        device.registerObserver(observer);
        device.connect();
        stream.setDroppedSampleCount(4);

        device.disconnect();

        assertThat(observer.flushes()).isEqualTo(1);
        assertThat(device.getStreamFailure()).isEqualTo("load cell stream is not open");
        assertThat(device.getDroppedSampleCount()).isZero();
    }

    @Test
    @Timeout(20)
    void aStreamThatFailsToStartIsReleasedAndNeverPublished() {
        var startFailure = new IllegalStateException("stream is already in progress");
        var failing = new ScriptedStream();
        failing.failOnStart(startFailure);
        var failingDevice = new LoadCellDevice(() -> failing);

        assertThatThrownBy(failingDevice::connect).isSameAs(startFailure);

        assertThat(failingDevice.isConnected()).isFalse();
        assertThat(failingDevice.getStreamFailure()).isEqualTo("load cell stream is not open");
        assertThat(failing.stopReadingCalls()).isEqualTo(1);
    }

    @Test
    @Timeout(20)
    void aFailedOpenLeavesTheNextConnectAbleToSucceed() {
        var failing = new ScriptedStream();
        failing.failOnStart(new IllegalStateException("stream is already in progress"));
        var healthy = new ScriptedStream();
        var handOut = new ArrayDeque<>(List.of(failing, healthy));
        var retryingDevice = new LoadCellDevice(handOut::poll);

        assertThatThrownBy(retryingDevice::connect).isInstanceOf(IllegalStateException.class);
        try {
            retryingDevice.connect();

            assertThat(retryingDevice.isConnected()).isTrue();
            assertThat(retryingDevice.getStreamFailure()).isNull();
        } finally {
            retryingDevice.disconnect();
        }
    }

    @Test
    @Timeout(20)
    void aStreamThatRefusesToStopStillCompletesTheTeardown() throws InterruptedException {
        var refusing = new ScriptedStream();
        refusing.failOnStop(new IllegalStateException("stream is already in progress"));
        var observer = new RecordingObserver(1);
        var refusingDevice = new LoadCellDevice(() -> refusing);
        refusingDevice.registerObserver(observer);
        refusingDevice.connect();
        refusing.enqueue(new Measurement(5f, 500L));
        assertThat(observer.awaitMeasurements(DELIVERY_TIMEOUT_MS)).isTrue();

        assertThatCode(refusingDevice::disconnect).doesNotThrowAnyException();

        assertThat(observer.flushes()).isEqualTo(1);
        assertThat(refusingDevice.getStreamFailure()).isEqualTo("load cell stream is not open");
        assertThat(refusingDevice.getLastDataNanos()).isZero();
    }

    @Test
    @Timeout(20)
    void aRefusedStopLeavesTheNextOpenAbleToSucceed() {
        var refusing = new ScriptedStream();
        refusing.failOnStop(new IllegalStateException("stream is already in progress"));
        var healthy = new ScriptedStream();
        var handOut = new ArrayDeque<>(List.of(refusing, healthy));
        var reopeningDevice = new LoadCellDevice(handOut::poll);
        reopeningDevice.connect();
        reopeningDevice.disconnect();

        try {
            reopeningDevice.connect();

            assertThat(healthy.startReadingCalls()).isEqualTo(1);
            assertThat(reopeningDevice.isConnected()).isTrue();
            assertThat(reopeningDevice.getStreamFailure()).isNull();
        } finally {
            reopeningDevice.disconnect();
        }
    }

    private static final class RecordingObserver implements MeasurementObserver {
        private final List<Measurement> received = new CopyOnWriteArrayList<>();
        private final CountDownLatch expected;
        private final AtomicInteger flushes = new AtomicInteger();

        private RecordingObserver(int expectedMeasurements) {
            this.expected = new CountDownLatch(expectedMeasurements);
        }

        @Override
        public void update(List<Measurement> measurements) {
            received.addAll(measurements);
            measurements.forEach(measurement -> expected.countDown());
        }

        @Override
        public void flush() {
            flushes.incrementAndGet();
        }

        boolean awaitMeasurements(long timeoutMs) throws InterruptedException {
            return expected.await(timeoutMs, TimeUnit.MILLISECONDS);
        }

        List<Measurement> received() {
            return List.copyOf(received);
        }

        int flushes() {
            return flushes.get();
        }
    }

    /** Delivers exactly what a test enqueues, and reports the liveness a test scripts. */
    private static final class ScriptedStream implements LoadCellStream {
        private final ConcurrentLinkedQueue<Measurement> queue = new ConcurrentLinkedQueue<>();
        private volatile boolean reading = false;
        private volatile StreamFailure lastError;
        private volatile long droppedSampleCount = 0;
        private volatile RuntimeException startFailure;
        private volatile RuntimeException stopFailure;
        private volatile Throwable drainFailure;
        private final AtomicInteger failingDrains = new AtomicInteger();
        private final AtomicInteger startReadingCalls = new AtomicInteger();
        private final AtomicInteger stopReadingCalls = new AtomicInteger();

        void enqueue(Measurement... measurements) {
            queue.addAll(List.of(measurements));
        }

        /** Rejects startReading() the way a driver rejects a stream whose lifecycle was crossed. */
        void failOnStart(RuntimeException failure) {
            startFailure = failure;
        }

        /** Ends the stream the way a driver does: reading stops, the cause stays readable. */
        void fail(StreamFailure failure) {
            lastError = failure;
            reading = false;
        }

        /** Rejects stopReading() the way a driver rejects a release it considers still in progress. */
        void failOnStop(RuntimeException failure) {
            stopFailure = failure;
        }

        /** Throws the given failure on the next {@code calls} drains, then delivers normally. */
        void failOnNextDrains(Throwable failure, int calls) {
            drainFailure = failure;
            failingDrains.set(calls);
        }

        void setDroppedSampleCount(long count) {
            droppedSampleCount = count;
        }

        int startReadingCalls() {
            return startReadingCalls.get();
        }

        int stopReadingCalls() {
            return stopReadingCalls.get();
        }

        @Override
        public void startReading() {
            if (startFailure != null) {
                throw startFailure;
            }
            startReadingCalls.incrementAndGet();
            reading = true;
        }

        @Override
        public void stopReading() {
            stopReadingCalls.incrementAndGet();
            if (stopFailure != null) {
                throw stopFailure;
            }
            reading = false;
        }

        @Override
        public List<Measurement> getNextValues() {
            if (failingDrains.getAndUpdate(left -> left > 0 ? left - 1 : 0) > 0) {
                throw sneaky(drainFailure);
            }
            List<Measurement> drained = new ArrayList<>();
            for (Measurement measurement = queue.poll(); measurement != null; measurement = queue.poll()) {
                drained.add(measurement);
            }
            return drained;
        }

        @Override
        public boolean isReading() {
            return reading;
        }

        @Override
        public StreamFailure lastError() {
            return lastError;
        }

        @Override
        public long droppedSampleCount() {
            return droppedSampleCount;
        }
    }

    /** Throws a checked exception from a method that declares none, as the Kotlin driver does. */
    @SuppressWarnings("unchecked")
    private static <T extends Throwable> RuntimeException sneaky(Throwable t) throws T {
        throw (T) t;
    }
}
