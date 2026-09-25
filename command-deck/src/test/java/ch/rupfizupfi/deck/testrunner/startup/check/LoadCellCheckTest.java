package ch.rupfizupfi.deck.testrunner.startup.check;

import ch.rupfizupfi.deck.device.DeviceService;
import ch.rupfizupfi.deck.device.api.LoadCellStream;
import ch.rupfizupfi.deck.device.api.LoadCellStreamProvider;
import ch.rupfizupfi.deck.device.api.Measurement;
import ch.rupfizupfi.deck.device.api.StreamFailure;
import ch.rupfizupfi.deck.device.loadcell.LoadCellDevice;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

/**
 * {@link LoadCellCheck}'s verdicts against a fake stream, including which references it leaves behind.
 */
class LoadCellCheckTest {

    /** The reader ticks every 20 ms; every wait here is a generous multiple of that. */
    private static final long DELIVERY_TIMEOUT_MS = 1000;

    private LoadCellDevice device;

    @AfterEach
    void releaseRemainingReference() {
        if (device != null && device.isConnected()) {
            device.disconnect();
        }
    }

    @Test
    @Timeout(20)
    void aCellThatCannotBeOpenedNamesTheFailureAndLeavesNoReferenceBehind() {
        device = new LoadCellDevice(refusing(new IllegalStateException("cell not plugged in")));

        assertThatThrownBy(() -> checkOver(device).execute())
                .isInstanceOf(CheckFailedException.class)
                .hasMessageContaining("IllegalStateException")
                .hasMessageContaining("cell not plugged in");
        assertThat(device.isConnected()).isFalse();
    }

    @Test
    @Timeout(20)
    void aMissingNativeBindingIsReportedRatherThanEscaping() {
        device = new LoadCellDevice(refusing(new UnsatisfiedLinkError("dscusb.dll")));

        assertThatThrownBy(() -> checkOver(device).execute())
                .isInstanceOf(CheckFailedException.class)
                .hasMessageContaining("UnsatisfiedLinkError")
                .hasMessageContaining("dscusb.dll");
        assertThat(device.isConnected()).isFalse();
    }

    @Test
    @Timeout(20)
    void aDeliveringCellPassesAndTheCheckBalancesItsOwnConnect() {
        device = new LoadCellDevice(() -> new FakeStream(true));

        assertThatCode(() -> checkOver(device).execute()).doesNotThrowAnyException();

        assertThat(device.isConnected()).isFalse();
    }

    @Test
    @Timeout(20)
    void aDeviceAnotherHolderKeepsOpenStaysOpenAfterTheCheck() throws InterruptedException {
        device = new LoadCellDevice(() -> new FakeStream(true));
        device.connect();
        awaitDataFlowing(device);

        assertThatCode(() -> checkOver(device).execute()).doesNotThrowAnyException();

        assertThat(device.isConnected()).isTrue();
    }

    @Test
    @Timeout(20)
    void aSilentCellIsRefusedAndReleased() {
        device = new LoadCellDevice(() -> new FakeStream(false));

        assertThatThrownBy(() -> checkOver(device).execute())
                .isInstanceOf(CheckFailedException.class)
                .hasMessageContaining("No measurement");
        assertThat(device.isConnected()).isFalse();
    }

    @Test
    @Timeout(20)
    void aFailingReleaseDoesNotMaskTheRefusal() {
        LoadCellDevice spied = spy(new LoadCellDevice(() -> new FakeStream(false)));
        doThrow(new IllegalStateException("release failed")).when(spied).disconnect();
        device = spied;

        assertThatThrownBy(() -> checkOver(spied).execute())
                .isInstanceOf(CheckFailedException.class)
                .hasMessageContaining("No measurement");

        doCallRealMethod().when(spied).disconnect();
    }

    private static LoadCellCheck checkOver(LoadCellDevice loadCell) {
        DeviceService deviceService = mock(DeviceService.class);
        when(deviceService.getLoadCell()).thenReturn(loadCell);
        return new LoadCellCheck(deviceService);
    }

    private static LoadCellStreamProvider refusing(Throwable failure) {
        return () -> {
            throw sneaky(failure);
        };
    }

    private static void awaitDataFlowing(LoadCellDevice cell) throws InterruptedException {
        long deadline = System.nanoTime() + DELIVERY_TIMEOUT_MS * 1_000_000L;
        while (!cell.isDataFlowing(500) && System.nanoTime() - deadline < 0) {
            Thread.sleep(20);
        }
        assertThat(cell.isDataFlowing(500)).isTrue();
    }

    /** Delivers on every read or never, the two cases the check distinguishes. */
    private static final class FakeStream implements LoadCellStream {
        private final Queue<Measurement> queued = new ConcurrentLinkedQueue<>();
        private final boolean delivering;
        private volatile boolean reading;

        private FakeStream(boolean delivering) {
            this.delivering = delivering;
        }

        @Override
        public void startReading() {
            reading = true;
        }

        @Override
        public void stopReading() {
            reading = false;
        }

        @Override
        public List<Measurement> getNextValues() {
            // Produced per read rather than seeded once, so a drained queue never looks like a cell
            // that has gone quiet.
            if (delivering && reading) {
                queued.add(new Measurement(12.5f, System.currentTimeMillis()));
            }

            List<Measurement> drained = new ArrayList<>();
            for (Measurement measurement = queued.poll(); measurement != null; measurement = queued.poll()) {
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
            return null;
        }

        @Override
        public long droppedSampleCount() {
            return 0;
        }
    }

    /** Throws a checked exception from a method that declares none, as the Kotlin driver does. */
    @SuppressWarnings("unchecked")
    private static <T extends Throwable> RuntimeException sneaky(Throwable t) throws T {
        throw (T) t;
    }
}
