package ch.rupfizupfi.deck.device.simulated;

import ch.rupfizupfi.deck.device.DeviceService;
import ch.rupfizupfi.deck.device.api.Drive;
import ch.rupfizupfi.deck.device.api.DriveCommunicationException;
import ch.rupfizupfi.deck.device.api.DriveDeadlineException;
import ch.rupfizupfi.deck.device.frequencyinverter.FrequencyInverterDevice;
import ch.rupfizupfi.deck.testrunner.DriveLinkProperties;
import ch.rupfizupfi.deck.testrunner.MotorSafetyController;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The simulated drive's link watchdog, and the proof that the simulated driver feeds it the way the
 * gated CFW11 driver does: a run with no dashboard open gets its keep-alive from the driver, the deck
 * sends none, and the watchdog still fires once nothing reaches the drive.
 */
class SimulatedLinkLossStopTest {

    private final SimulatedFaultSwitches faults = new SimulatedFaultSwitches();
    private final SimulatedBench bench = new SimulatedBench(new SimulatedBenchProperties(), faults);
    private final SimulatedDriveProvider provider = new SimulatedDriveProvider(bench, faults);
    /** Every handle a case opens; each holds a daemon I/O thread until it is closed. */
    private final List<Drive> opened = new ArrayList<>();

    @AfterEach
    void stopBench() {
        opened.forEach(Drive::close);
        bench.stop();
    }

    private Drive open() {
        Drive drive = provider.open();
        opened.add(drive);
        return drive;
    }

    private static void energize(Drive drive) {
        drive.setSpeedReferenceValueAsRpm(100);
        drive.setGeneralEnable(true);
        drive.setStart(true);
    }

    @Test
    void silenceLongerThanTheTimeoutRemovesGeneralEnable() {
        Drive drive = open();
        drive.setActionInCaseOfCommunicationError(Drive.COMMUNICATION_ERROR_DISABLE_VIA_GENERAL_ENABLE);
        drive.enableLinkLossStop(Duration.ofMillis(250));
        energize(drive);
        long lastRequest = System.nanoTime();

        // Rounded up to the drive's tenth of a second, as the CFW11 counts.
        assertThat(bench.linkLossTimeoutNanos()).isEqualTo(TimeUnit.MILLISECONDS.toNanos(300));

        bench.checkLinkWatchdog(lastRequest + TimeUnit.MILLISECONDS.toNanos(250));
        assertThat(bench.generalEnabled()).isTrue();

        bench.checkLinkWatchdog(lastRequest + TimeUnit.MILLISECONDS.toNanos(400));
        assertThat(bench.generalEnabled()).isFalse();
        assertThat(bench.linkLossTripped()).isTrue();
    }

    @Test
    void anUnarmedDriveNeverStopsItself() {
        Drive drive = open();
        drive.setActionInCaseOfCommunicationError(Drive.COMMUNICATION_ERROR_DISABLE_VIA_GENERAL_ENABLE);
        energize(drive);

        bench.checkLinkWatchdog(System.nanoTime() + TimeUnit.HOURS.toNanos(1));

        // The CFW11 default: P0314 = 0.0 leaves the configured action inert.
        assertThat(bench.generalEnabled()).isTrue();
    }

    @Test
    void aRequestThatNeverReachesTheDriveDoesNotFeedIt() {
        Drive drive = open();
        drive.setActionInCaseOfCommunicationError(Drive.COMMUNICATION_ERROR_DISABLE_VIA_GENERAL_ENABLE);
        drive.enableLinkLossStop(Duration.ofMillis(100));
        energize(drive);
        long lastRequest = System.nanoTime();

        faults.arm(SimulatedFault.DRIVE_UNRESPONSIVE);
        assertThatThrownBy(drive::getMotorSpeedValueAsRpm).isInstanceOf(DriveCommunicationException.class);

        bench.checkLinkWatchdog(lastRequest + TimeUnit.MILLISECONDS.toNanos(150));
        assertThat(bench.generalEnabled()).isFalse();
    }

    @Test
    void armingIsRefusedWhenTheActionWouldNotStopTheMotor() {
        Drive drive = open();
        drive.setActionInCaseOfCommunicationError(0);

        assertThatThrownBy(() -> drive.enableLinkLossStop(Duration.ofSeconds(2)))
                .isInstanceOf(IllegalStateException.class);
        assertThat(bench.linkLossTimeoutNanos()).isZero();
    }

    @Test
    void aTimeoutOutsideTheDrivesRangeIsRefused() {
        Drive drive = open();
        drive.setActionInCaseOfCommunicationError(Drive.COMMUNICATION_ERROR_DISABLE_VIA_GENERAL_ENABLE);

        assertThatThrownBy(() -> drive.enableLinkLossStop(Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> drive.enableLinkLossStop(Duration.ofSeconds(-1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> drive.enableLinkLossStop(Duration.ofMillis(999_001)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** Traffic stops: the link drops, the driver's injected reads reach nothing, the drive coasts. */
    @Test
    @Timeout(value = 10, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void onceNothingReachesTheDriveARunningBenchStopsItself() throws InterruptedException {
        bench.start();
        Drive drive = open();
        drive.setActionInCaseOfCommunicationError(Drive.COMMUNICATION_ERROR_DISABLE_VIA_GENERAL_ENABLE);
        drive.enableLinkLossStop(Duration.ofMillis(200));
        energize(drive);

        faults.arm(SimulatedFault.DRIVE_UNRESPONSIVE);
        assertThat(awaitTrue(() -> !bench.generalEnabled(), 3000)).isTrue();
        assertThat(bench.linkLossTripped()).isTrue();
    }

    /** The driver's keep-alive is armed by enableLinkLossStop and needs no traffic from the deck. */
    @Test
    @Timeout(value = 10, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void theDriversKeepAliveFeedsAnArmedIdleHandle() throws InterruptedException {
        bench.start();
        Drive drive = open();
        SimulatedLinkKeepAlive keepAlive = ((SimulatedDrive) drive).keepAlive();
        assertThat(keepAlive.feeding()).as("not before it is armed").isFalse();
        drive.setActionInCaseOfCommunicationError(Drive.COMMUNICATION_ERROR_DISABLE_VIA_GENERAL_ENABLE);
        drive.enableLinkLossStop(Duration.ofMillis(300));
        energize(drive);
        assertThat(keepAlive.feeding()).isTrue();

        // Five timeouts, and the deck sends nothing at all.
        Thread.sleep(1_500);
        assertThat(bench.generalEnabled()).isTrue();
        assertThat(bench.linkLossTripped()).isFalse();
    }

    /**
     * Like the CFW11 driver's, a de-energizing transaction pauses the keep-alive until the next arm:
     * after a stop only the deck's own traffic feeds the watchdog, and a closed handle never feeds it.
     */
    @Test
    @Timeout(value = 10, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void aStopPausesTheKeepAliveUntilTheNextArmAndCloseEndsIt() throws InterruptedException {
        bench.start();
        Drive drive = open();
        SimulatedLinkKeepAlive keepAlive = ((SimulatedDrive) drive).keepAlive();
        drive.setActionInCaseOfCommunicationError(Drive.COMMUNICATION_ERROR_DISABLE_VIA_GENERAL_ENABLE);
        drive.enableLinkLossStop(Duration.ofMillis(300));
        energize(drive);

        drive.stop();
        assertThat(keepAlive.feeding()).isFalse();
        assertThat(awaitTrue(bench::linkLossTripped, 2000)).as("nothing fed the watchdog after the stop").isTrue();

        drive.enableLinkLossStop(Duration.ofMillis(300));
        assertThat(keepAlive.feeding()).isTrue();
        Thread.sleep(900);
        assertThat(bench.linkLossTripped()).as("re-armed, fed again").isFalse();

        drive.close();
        assertThat(keepAlive.feeding()).isFalse();
        assertThat(awaitTrue(() -> !keepAlive.running(), 3000)).as("the keep-alive thread ended").isTrue();
    }

    @Test
    @Timeout(value = 20, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void theDriversKeepAliveHoldsAnEnergizedRunAndTheDriveStopsOnceItCannotGetThrough()
            throws InterruptedException {
        bench.start();
        var frequencyInverter = new FrequencyInverterDevice(provider);
        var deviceService = mock(DeviceService.class);
        when(deviceService.getFrequencyInverter()).thenReturn(frequencyInverter);
        var properties = new DriveLinkProperties();
        properties.setLinkLossTimeout(Duration.ofSeconds(1));
        var motorSafety = new MotorSafetyController(deviceService, provider, properties);
        frequencyInverter.connect();
        try {
            motorSafety.energize(SimulatedLinkLossStopTest::energize);

            // Two and a half timeouts with no observer registered, so the info poll never runs and
            // the deck sends nothing: only the driver's keep-alive talks to the drive.
            Thread.sleep(2500);
            assertThat(bench.generalEnabled()).isTrue();
            assertThat(bench.linkLossTripped()).isFalse();

            // USB loss: the keep-alive keeps trying, but nothing reaches the drive.
            faults.arm(SimulatedFault.DRIVE_UNRESPONSIVE);
            assertThat(awaitTrue(() -> !bench.generalEnabled(), 5000)).isTrue();
            assertThat(bench.linkLossTripped()).isTrue();
        } finally {
            faults.clearAll();
            motorSafety.safeStop("test teardown");
            frequencyInverter.disconnect();
        }
    }

    @Test
    @Timeout(value = 20, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void reEnergizingOverACoastingShaftIsNotRefused() throws InterruptedException {
        bench.start();
        var frequencyInverter = new FrequencyInverterDevice(provider);
        var deviceService = mock(DeviceService.class);
        when(deviceService.getFrequencyInverter()).thenReturn(frequencyInverter);
        var motorSafety = new MotorSafetyController(deviceService, provider, new DriveLinkProperties());
        frequencyInverter.connect();
        try {
            motorSafety.energize(SimulatedLinkLossStopTest::energize);
            assertThat(awaitTrue(() -> bench.measuredRpm() > 0, 3000)).isTrue();
            // The shaft keeps turning after the hold de-energizes it, as a coasting mass does.
            faults.arm(SimulatedFault.DRIVE_MOTOR_NEVER_SLOWS);
            motorSafety.withDrive(drive -> drive.setGeneralEnable(false));

            // A resume re-sends the same action and timeout; unchanged CFG values are not rewritten.
            motorSafety.energize(SimulatedLinkLossStopTest::energize);
            assertThat(bench.measuredRpm()).isNotZero();
            assertThat(bench.generalEnabled()).isTrue();

            // A changed CFG value is still refused while the shaft turns.
            assertThatThrownBy(() -> motorSafety.withDrive(drive -> drive.setActionInCaseOfCommunicationError(1)))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("CFG");
        } finally {
            faults.clearAll();
            motorSafety.safeStop("test teardown");
            frequencyInverter.disconnect();
        }
    }

    /**
     * The bus stop is latency, the drive's watchdog is the guarantee: a transaction wedged longer
     * than the link-loss timeout holds every call behind it, the stop included, and the drive
     * coasts the motor on its own before the stop's deadline runs out.
     */
    @Test
    @Timeout(value = 20, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void aWedgedTransactionStarvesTheWatchdogAndTheMotorCoasts() throws InterruptedException {
        bench.start();
        Drive drive = open();
        drive.setActionInCaseOfCommunicationError(Drive.COMMUNICATION_ERROR_DISABLE_VIA_GENERAL_ENABLE);
        drive.enableLinkLossStop(Duration.ofSeconds(1));
        energize(drive);

        faults.arm(SimulatedFault.DRIVE_TRANSACTION_WEDGED);
        long armed = System.nanoTime();
        drive.async().getMotorData();
        Thread.sleep(50);

        long stopSubmitted = System.nanoTime();
        assertThatThrownBy(drive::stop).isInstanceOf(DriveDeadlineException.class);
        long stopFailedAfterMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - stopSubmitted);

        assertThat(stopFailedAfterMs).as("the STOP lane's deadline").isBetween(1_400L, 2_500L);
        assertThat(bench.linkLossTripped()).as("the drive's watchdog fired first").isTrue();
        assertThat(bench.generalEnabled()).as("and coasted the motor").isFalse();

        // Once the wedge (3 s by default) ends, the stop that missed its deadline still goes out,
        // and the handle answers again.
        long wedgeMillis = new SimulatedBenchProperties().getWedgeMillis();
        assertThat(awaitTrue(() -> !bench.started() && bench.speedReferenceRpm() == 0,
                wedgeMillis + 2_000)).as("the late stop reached the drive").isTrue();
        assertThat(System.nanoTime() - armed).as("not before the wedge ended")
                .isGreaterThanOrEqualTo(TimeUnit.MILLISECONDS.toNanos(wedgeMillis));
        assertThat(drive.getControlParameters()).containsEntry("start", false);
        drive.close();
        assertThatThrownBy(drive::getMotorData).as("the port is closed once close returns")
                .isInstanceOf(IllegalStateException.class);
    }

    private static boolean awaitTrue(BooleanSupplier condition, long timeoutMs) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            Thread.sleep(10);
        }
        return condition.getAsBoolean();
    }
}
