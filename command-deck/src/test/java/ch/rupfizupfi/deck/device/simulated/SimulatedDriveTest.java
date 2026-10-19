package ch.rupfizupfi.deck.device.simulated;

import ch.rupfizupfi.deck.device.api.Drive;
import ch.rupfizupfi.deck.device.api.DriveClosePendingException;
import ch.rupfizupfi.deck.device.api.DriveDeadlineException;
import ch.rupfizupfi.deck.device.api.DriveGating;
import ch.rupfizupfi.deck.device.api.DriveLane;
import ch.rupfizupfi.deck.device.api.support.GatedDrive;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The simulator's bus model: per-transaction latency and the one-shot, releasable wedge. */
@Timeout(value = 20, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class SimulatedDriveTest {

    private static final long WEDGE_MILLIS = 400;
    /** What a timing case leaves between a wedge and the deadline it must beat. */
    private static final long MARGIN_MILLIS = 250;

    private final SimulatedFaultSwitches faults = new SimulatedFaultSwitches();
    private final SimulatedBenchProperties properties = new SimulatedBenchProperties();
    private SimulatedBench bench;
    private Drive drive;

    @AfterEach
    void close() {
        if (bench != null) {
            bench.endWedge();
        }
        if (drive != null) {
            drive.close();
        }
    }

    private Drive open() {
        bench = new SimulatedBench(properties, faults);
        drive = new SimulatedDriveProvider(bench, faults).open();
        return drive;
    }

    @Test
    void theDefaultLatencyIsAFewMillisecondsAndComesFromTheProperties() {
        assertThat(new SimulatedBenchProperties().getTransactionLatencyMillis()).isEqualTo(3);

        properties.setTransactionLatencyMillis(7);
        open();

        assertThat(bench.transactionLatency()).isEqualTo(Duration.ofMillis(7));
        assertThat(drive.gating()).isEqualTo(DriveGating.DRIVER_SERIALIZES);
    }

    @Test
    void everyTransactionTakesTheLatency() {
        properties.setTransactionLatencyMillis(0);
        open();
        bench.setTransactionLatency(Duration.ofMillis(60));

        long started = System.nanoTime();
        drive.getMotorData();
        drive.getMotorData();

        assertThat(millisSince(started)).isGreaterThanOrEqualTo(120);
    }

    /**
     * The wedged read must still answer inside the STATUS deadline, so the wedge here leaves the
     * same 250 ms margin under it as the other timing cases leave.
     */
    @Test
    void theWedgeHoldsExactlyTheNextTransactionBeforeItReachesTheDrive() throws InterruptedException {
        long wedgeMillis = SimulatedDrive.DEADLINES.status().toMillis() - MARGIN_MILLIS;
        properties.setTransactionLatencyMillis(0);
        properties.setWedgeMillis(wedgeMillis);
        open();
        drive.getMotorData();
        long lastRequest = bench.lastRequestNanos();

        faults.arm(SimulatedFault.DRIVE_TRANSACTION_WEDGED);
        long started = System.nanoTime();
        var wedged = drive.async().getMotorData();
        Thread.sleep(wedgeMillis / 4);

        assertThat(faults.isActive(SimulatedFault.DRIVE_TRANSACTION_WEDGED))
                .as("consumed by the transaction that took it").isFalse();
        assertThat(bench.lastRequestNanos()).as("nothing reached the drive while wedged").isEqualTo(lastRequest);

        wedged.join();
        assertThat(millisSince(started)).as("the wedged transaction").isGreaterThanOrEqualTo(wedgeMillis);
        assertThat(bench.lastRequestNanos()).as("it reached the drive once released").isGreaterThan(lastRequest);

        long next = System.nanoTime();
        drive.getMotorData();
        assertThat(millisSince(next)).as("the transaction after it").isLessThan(wedgeMillis / 2);
    }

    /**
     * close() during a wedge inside the 1500 ms close bound returns only once the wedged transaction
     * ended, the stop queued behind it went out and the port closed, so a fresh handle opened right
     * after never overlaps it.
     */
    @Test
    void closeDuringAWedgeReturnsOnceItEndedTheQueuedStopWentOutAndThePortClosed() {
        properties.setTransactionLatencyMillis(0);
        properties.setWedgeMillis(WEDGE_MILLIS);
        open();
        drive.setSpeedReferenceValueAsRpm(300);
        drive.setControlParameters(true, true, null, null, null);
        faults.arm(SimulatedFault.DRIVE_TRANSACTION_WEDGED);
        long started = System.nanoTime();
        var wedged = drive.async().getMotorData();
        sleep(WEDGE_MILLIS / 4);
        var stop = drive.async().stop();

        drive.close();

        assertThat(millisSince(started)).as("close outlasted the wedge").isGreaterThanOrEqualTo(WEDGE_MILLIS);
        assertThat(bench.started()).as("the queued stop reached the plant before close returned").isFalse();
        assertThat(bench.generalEnabled()).isFalse();
        assertThat(bench.speedReferenceRpm()).isZero();
        assertThatThrownBy(drive::getMotorData).isInstanceOf(IllegalStateException.class);
        assertThat(wedged.join()).containsKey("speed");
        stop.join();

        Drive fresh = new SimulatedDriveProvider(bench, faults).open();
        try {
            assertThat(fresh.getControlParameters()).containsEntry("start", false);
        } finally {
            fresh.close();
        }
    }

    /**
     * A wedge past the 1500 ms close bound: close() gives up and says so, the port stays held, and
     * the handle closes it by itself once the wedge ends - after the stop queued behind it went out.
     */
    @Test
    void closeDuringAWedgeLongerThanTheCloseBoundThrowsPendingAndClosesThePortWhenItEnds() {
        long wedgeMillis = DriveLane.STOP_DEADLINE_MILLIS + 500;
        properties.setTransactionLatencyMillis(0);
        properties.setWedgeMillis(wedgeMillis);
        open();
        drive.setSpeedReferenceValueAsRpm(300);
        drive.setControlParameters(true, true, null, null, null);
        faults.arm(SimulatedFault.DRIVE_TRANSACTION_WEDGED);
        long started = System.nanoTime();
        var wedged = drive.async().getMotorData();
        sleep(50);
        drive.async().stop();

        assertThatThrownBy(drive::close).isInstanceOf(DriveClosePendingException.class);
        assertThat(millisSince(started)).as("close gave up at its bound, inside the wedge")
                .isLessThan(wedgeMillis);
        assertThat(bench.started()).as("the queued stop has not gone out yet").isTrue();
        assertThatThrownBy(drive::getMotorData).isInstanceOf(IllegalStateException.class);

        assertThatThrownBy(wedged::join).as("its caller gave up at the STATUS deadline")
                .hasCauseInstanceOf(DriveDeadlineException.class);
        assertThatCode(drive::close).as("a close once the wedge ended").doesNotThrowAnyException();
        assertThat(millisSince(started)).isGreaterThanOrEqualTo(wedgeMillis);
        assertThat(bench.started()).as("the queued stop reached the plant before the port closed").isFalse();
        assertThat(bench.generalEnabled()).isFalse();
    }

    @Test
    void endWedgeReleasesTheHeldTransactionAtOnce() {
        properties.setTransactionLatencyMillis(0);
        properties.setWedgeMillis(Long.MAX_VALUE);
        assertThat(properties.getWedgeMillis()).as("clamped to a day").isEqualTo(SimulatedBenchProperties.MAX_WEDGE_MILLIS);
        open();
        faults.arm(SimulatedFault.DRIVE_TRANSACTION_WEDGED);
        var wedged = drive.async().getMotorData();
        sleep(100);
        assertThat(wedged).as("held by the wedge").isNotDone();

        bench.endWedge();

        assertThat(wedged.join()).containsKey("speed");
        assertThat(drive.getMotorData()).as("the next transaction is not wedged").containsKey("speed");
    }

    @Test
    void eachHandleHasItsOwnNamedIoThread() {
        open();
        var other = (GatedDrive) new SimulatedDriveProvider(bench, faults).open();
        try {
            String mine = ((GatedDrive) drive).ioThreadName();

            assertThat(mine).matches("simulated-drive-io-[0-9]+");
            assertThat(other.ioThreadName()).matches("simulated-drive-io-[0-9]+").isNotEqualTo(mine);
        } finally {
            other.close();
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private static long millisSince(long startedNanos) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos);
    }
}
