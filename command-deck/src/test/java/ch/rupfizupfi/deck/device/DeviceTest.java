package ch.rupfizupfi.deck.device;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The reference counting of {@link Device}, including a handle lost out of band (OQ-68), and its
 * lifecycle lock (BL-212).
 */
class DeviceTest {

    @Test
    void aLostHandleKeepsItsHoldersSoAStaleDisconnectCannotCloseTheReopenedSession() {
        var device = new CountingDevice();
        device.connect(); // A
        device.connect(); // B

        device.markConnectionLost();
        assertThat(device.isConnected()).isFalse();

        device.connect(); // C
        assertThat(device.opens).isEqualTo(2);
        assertThat(device.isConnected()).isTrue();

        device.disconnect(); // A
        device.disconnect(); // B
        assertThat(device.closes).isZero();
        assertThat(device.isConnected()).isTrue();

        device.disconnect(); // C
        assertThat(device.closes).isEqualTo(1);
        assertThat(device.isConnected()).isFalse();
    }

    @Test
    void firstConnectOpensAndLastDisconnectCloses() {
        var device = new CountingDevice();
        device.connect();
        device.connect();
        assertThat(device.opens).isEqualTo(1);
        assertThat(device.isConnected()).isTrue();

        device.disconnect();
        assertThat(device.closes).isZero();
        device.disconnect();
        assertThat(device.closes).isEqualTo(1);
        assertThat(device.isConnected()).isFalse();

        assertThatCode(device::disconnect).doesNotThrowAnyException();
        assertThat(device.closes).isEqualTo(1);
    }

    @Test
    void aFailedOpenLeavesNoReferenceAndTheNextConnectRetries() {
        var device = new CountingDevice();
        device.failNextOpen = true;
        assertThatThrownBy(device::connect).isInstanceOf(IllegalStateException.class);
        assertThat(device.isConnected()).isFalse();

        // Nothing to balance: a disconnect now is the clamped no-op, not a close.
        device.disconnect();
        assertThat(device.closes).isZero();

        device.connect();
        assertThat(device.opens).isEqualTo(2);
        assertThat(device.isConnected()).isTrue();
        device.disconnect();
        assertThat(device.closes).isEqualTo(1);
    }

    @Test
    void resetRebuildsTheConnectionAndKeepsTheCount() {
        var device = new CountingDevice();
        device.connect();
        device.connect();

        device.reset();
        assertThat(device.closes).isEqualTo(1);
        assertThat(device.opens).isEqualTo(2);
        assertThat(device.isConnected()).isTrue();

        device.disconnect();
        assertThat(device.closes).isEqualTo(1);
        device.disconnect();
        assertThat(device.closes).isEqualTo(2);
    }

    @Test
    void resetWithoutHoldersIsRefused() {
        assertThatThrownBy(new CountingDevice()::reset).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void aFailedReopenOfALostHandleKeepsTheHoldersAndStaysLost() {
        var device = new CountingDevice();
        device.connect();
        device.markConnectionLost();

        device.failNextOpen = true;
        assertThatThrownBy(device::connect).isInstanceOf(IllegalStateException.class);
        assertThat(device.isConnected()).isFalse();

        device.connect();
        assertThat(device.opens).isEqualTo(3);
        assertThat(device.isConnected()).isTrue();

        device.disconnect();
        assertThat(device.closes).isZero();
        device.disconnect();
        assertThat(device.closes).isEqualTo(1);
    }

    @Test
    void theLastHolderOfALostHandleStillClosesIt() {
        // A lost mark set while the handle is still open leaves it live; the last disconnect is
        // what closes it.
        var device = new CountingDevice();
        device.connect();
        device.connect();
        device.markConnectionLost();

        device.disconnect();
        assertThat(device.closes).isZero();
        device.disconnect();
        assertThat(device.closes).isEqualTo(1);
        assertThat(device.isConnected()).isFalse();

        device.connect();
        assertThat(device.opens).isEqualTo(2);
        device.disconnect();
        assertThat(device.closes).isEqualTo(2);
    }

    // ---- the lifecycle lock (BL-212) ------------------------------------------------------------

    @Test
    @Timeout(value = 20, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void connectDisconnectAndResetWaitOnTheLifecycleLock() throws InterruptedException {
        var device = new CountingDevice();
        // Two holders, so the three below leave two whatever order they run in.
        device.connect();
        device.connect();
        assertThat(device.tryLockLifecycle("test hold", 0)).isTrue();
        assertThat(device.describeLifecycleHolder()).startsWith("test hold on thread '"
                + Thread.currentThread().getName() + "', held for ");
        Thread connector;
        Thread resetter;
        Thread disconnector;
        try {
            connector = Thread.ofPlatform().name("connector").start(device::connect);
            resetter = Thread.ofPlatform().name("resetter").start(device::reset);
            disconnector = Thread.ofPlatform().name("disconnector").start(device::disconnect);
            awaitParked(connector);
            awaitParked(resetter);
            awaitParked(disconnector);
            assertThat(device.opens).as("nothing ran past the lock").isEqualTo(1);
            assertThat(device.closes).isZero();
        } finally {
            device.unlockLifecycle();
        }
        connector.join(5_000);
        resetter.join(5_000);
        disconnector.join(5_000);

        assertThat(device.describeLifecycleHolder()).as("released by the last holder").isNull();
        assertThat(device.isConnected()).isTrue();
        device.disconnect();
        device.disconnect();
        assertThat(device.isConnected()).isFalse();
    }

    @Test
    void theOutermostAcquireNamesTheHolder() {
        var device = new CountingDevice();
        device.lockLifecycle("reset()");
        try {
            device.connect();
            assertThat(device.describeLifecycleHolder()).startsWith("reset() on thread");
        } finally {
            device.unlockLifecycle();
        }
        assertThat(device.describeLifecycleHolder()).isNull();
        device.disconnect();
    }

    @Test
    @Timeout(value = 20, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void aTimedOutTryLockReportsFalseAndNamesTheHolder() throws InterruptedException {
        var device = new CountingDevice();
        var held = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        Thread holder = Thread.ofPlatform().name("dashboard-connect").start(() -> {
            device.lockLifecycle("connect()");
            try {
                held.countDown();
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                device.unlockLifecycle();
            }
        });
        assertThat(held.await(5, TimeUnit.SECONDS)).isTrue();

        long started = System.nanoTime();
        assertThat(device.tryLockLifecycle("safe stop tier 2", 200)).isFalse();
        long tookMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        assertThat(device.describeLifecycleHolder()).startsWith("connect() on thread 'dashboard-connect'");
        release.countDown();
        holder.join(5_000);

        assertThat(tookMs).isGreaterThanOrEqualTo(199).isLessThan(1_000);
        assertThat(device.tryLockLifecycle("safe stop tier 2", 200)).isTrue();
        device.unlockLifecycle();
    }

    /**
     * The order is lifecycle lock, then monitor: a {@code connect()} parked on the lifecycle lock
     * holds no monitor, and the monitor-only {@code markConnectionLost()} never waits on the
     * lifecycle lock. Either one reversed deadlocks against tier 2, which holds both.
     */
    @Test
    @Timeout(value = 20, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void aThreadWaitingForTheLifecycleLockHoldsNoMonitorAndTheMonitorNeverWaitsForIt()
            throws InterruptedException {
        var device = new CountingDevice();
        device.connect();
        var held = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        Thread holder = Thread.ofPlatform().name("lock-holder").start(() -> {
            device.lockLifecycle("disconnect()");
            try {
                held.countDown();
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                device.unlockLifecycle();
            }
        });
        assertThat(held.await(5, TimeUnit.SECONDS)).isTrue();
        Thread connector = Thread.ofPlatform().name("connector").start(device::connect);
        awaitParked(connector);

        try {
            synchronized (device) {
                device.markConnectionLost();
            }
            assertThat(device.isConnected()).isFalse();
        } finally {
            release.countDown();
        }
        holder.join(5_000);
        connector.join(5_000);

        assertThat(connector.isAlive()).isFalse();
        assertThat(device.opens).as("the connect re-opened the lost handle").isEqualTo(2);
        device.disconnect();
        device.disconnect();
        assertThat(device.closes).isEqualTo(1);
    }

    /** Parked on a {@code java.util.concurrent} lock, not blocked on a monitor. */
    private static void awaitParked(Thread thread) {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (thread.getState() != Thread.State.WAITING && System.nanoTime() < until) {
            Thread.onSpinWait();
        }
        assertThat(thread.getState()).as(thread.getName()).isEqualTo(Thread.State.WAITING);
    }

    private static final class CountingDevice extends Device {
        int opens;
        int closes;
        boolean failNextOpen;

        @Override
        protected void openConnection() {
            opens++;
            if (failNextOpen) {
                failNextOpen = false;
                throw new IllegalStateException("device refuses to open");
            }
        }

        @Override
        protected void closeConnection() {
            closes++;
        }
    }
}
