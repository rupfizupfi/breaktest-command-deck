package ch.rupfizupfi.deck.device.frequencyinverter;

import ch.rupfizupfi.deck.device.Device.LifecycleLockTimeoutException;
import ch.rupfizupfi.deck.device.api.ControlSource;
import ch.rupfizupfi.deck.device.api.Drive;
import ch.rupfizupfi.deck.device.api.DriveAsync;
import ch.rupfizupfi.deck.device.api.DriveClosePendingException;
import ch.rupfizupfi.deck.device.api.DriveGating;
import ch.rupfizupfi.deck.device.api.DriveLane;
import ch.rupfizupfi.deck.device.simulated.SimulatedBench;
import ch.rupfizupfi.deck.device.simulated.SimulatedBenchProperties;
import ch.rupfizupfi.deck.device.simulated.SimulatedDriveProvider;
import ch.rupfizupfi.deck.device.simulated.SimulatedFaultSwitches;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FrequencyInverterDeviceTest {

    private static final int FAILING_CALLS = 2;
    /** Long enough for the 400 ms poll cadence to get past {@link #FAILING_CALLS} failures. */
    private static final long INFO_TIMEOUT_SECONDS = 3;
    /** Far longer than the gate's hand-off of an idle lane's call to its I/O thread, far shorter than a transaction. */
    private static final long SETTLE_MS = 60;

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
        assertThat(info.motorCurrent).isEqualTo(0.3);
        assertThat(info.motorVoltage).isEqualTo(400);
        assertThat(info.motorTorquePercent).isEqualTo(1.2);
        assertThat(info.start).isTrue();
        assertThat(info.generalEnable).isTrue();
        assertThat(info.useSecondRamp).isFalse();
        assertThat(info.directionIsForward).isTrue();

        motorData.remove("torque");
        assertThatThrownBy(() -> FrequencyInverterDevice.toInfo(motorData, controlParameters, 8))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("torque");
    }

    /** Drive#getMotorData: current and torque arrive in tenths; Info carries A and % of rated torque. */
    @Test
    void toInfoScalesTenthsToEngineeringUnits() {
        var controlParameters = Map.of("start", true, "generalEnable", true, "useSecondRamp", false,
                "directionIsForward", true);

        var full = FrequencyInverterDevice.toInfo(
                Map.of("speed", 980, "current", 45000, "voltage", 380, "torque", 10000), controlParameters, 1);
        assertThat(full.motorCurrent).isEqualTo(4500.0);
        assertThat(full.motorTorquePercent).isEqualTo(1000.0);
        assertThat(full.motorVoltage).isEqualTo(380);
        assertThat(full.speed).isEqualTo(980);

        var braking = FrequencyInverterDevice.toInfo(
                Map.of("speed", 980, "current", 35, "voltage", 380, "torque", -50), controlParameters, 2);
        assertThat(braking.motorCurrent).isEqualTo(3.5);
        assertThat(braking.motorTorquePercent).isEqualTo(-5.0);
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

    @Test
    void openingOverAHandleMarkedLostClosesItFirst() {
        // H-09: a handle can be marked lost while still open (a lost mark without its close), so
        // openConnection must close it first and never stack a second session.
        List<StubDrive> handles = new CopyOnWriteArrayList<>();
        var live = new AtomicInteger();
        var maxLive = new AtomicInteger();
        var device = new FrequencyInverterDevice(() -> {
            var handle = new StubDrive() {
                @Override
                public void close() {
                    if (!isClosed()) {
                        live.decrementAndGet();
                    }
                    super.close();
                }
            };
            handles.add(handle);
            maxLive.accumulateAndGet(live.incrementAndGet(), Math::max);
            return handle;
        });

        device.connect();
        device.markHandleLost();
        device.connect();

        assertThat(handles).hasSize(2);
        assertThat(handles.getFirst().isClosed()).isTrue();
        assertThat(handles.getLast().isClosed()).isFalse();
        assertThat(maxLive).as("sessions open on the drive at once").hasValue(1);
        // A lost handle keeps its holder (OQ-68): both references must be released.
        device.disconnect();
        assertThat(live).hasValue(1);
        device.disconnect();
        assertThat(live).hasValue(0);
    }

    @Test
    void aRunEndingAfterASafeStopLeavesTheDashboardItsHandle() {
        // OQ-68: the run's disconnect used to reach zero and close the poll's re-opened handle.
        List<StubDrive> handles = new CopyOnWriteArrayList<>();
        var device = new FrequencyInverterDevice(() -> {
            var handle = new StubDrive();
            handles.add(handle);
            return handle;
        });
        device.connect(); // the dashboard

        device.invalidateConnection();
        device.connect(); // the run
        device.disconnect();

        assertThat(device.isConnected()).isTrue();
        assertThat(device.isDriveHandleOpen()).isTrue();
        assertThat(handles).hasSize(2);
        assertThat(handles.getLast().isClosed()).isFalse();

        device.disconnect();
        assertThat(handles.getLast().isClosed()).isTrue();
    }

    // ---- a close that is still pending (BL-135/BL-148) ----------------------------------------

    @Test
    void closeDriveHandleReportsPendingDropsTheHandleAndNeverRetriesTheClose() {
        var handle = new PendingCloseDrive();
        var device = new FrequencyInverterDevice(() -> handle);
        device.connect();

        assertThat(device.closeDriveHandle()).isEqualTo(FrequencyInverterDevice.HandleClose.PENDING);
        assertThat(device.isDriveHandleOpen()).as("dropped all the same").isFalse();
        assertThatThrownBy(() -> device.withDrive(d -> {
        })).isInstanceOf(DriveUnavailableException.class);

        assertThat(device.closeDriveHandle()).as("a second call: still held, answered at once")
                .isEqualTo(FrequencyInverterDevice.HandleClose.PENDING);
        assertThat(handle.closeAttempts).as("the stop path pays the close bound once").hasValue(1);
    }

    @Test
    void openConnectionRetriesThePendingCloseAndRefusesWithDriveUnavailableWhileThePortIsStillHeld() {
        var opens = new AtomicInteger();
        var first = new PendingCloseDrive();
        var device = new FrequencyInverterDevice(() -> opens.incrementAndGet() == 1 ? first : new StubDrive());
        device.connect();
        device.invalidateConnection();

        assertThatThrownBy(device::connect).isInstanceOf(DriveUnavailableException.class)
                .hasMessageContaining("still in flight");

        assertThat(opens).as("no second session beside the held port").hasValue(1);
        assertThat(first.closeAttempts).as("the connect retried the close").hasValue(2);
        assertThat(device.isConnected()).isFalse();
        assertThat(device.isDriveHandleOpen()).isFalse();
    }

    @Test
    void openConnectionOpensOnceThePendingCloseFinished() {
        var opens = new AtomicInteger();
        var first = new PendingCloseDrive();
        var device = new FrequencyInverterDevice(() -> opens.incrementAndGet() == 1 ? first : new StubDrive());
        device.connect();
        device.invalidateConnection();
        assertThatThrownBy(device::connect).isInstanceOf(DriveUnavailableException.class);

        first.transferEnded = true;
        device.connect();

        assertThat(opens).hasValue(2);
        assertThat(first.isClosed()).isTrue();
        assertThat(device.isConnected()).isTrue();
        assertThat(device.isDriveHandleOpen()).isTrue();
        device.disconnect();
    }

    @Test
    void aParkedFreshHandleHoldsOffTheNextOpenUntilItsPortClosed() {
        var opens = new AtomicInteger();
        var device = new FrequencyInverterDevice(() -> {
            opens.incrementAndGet();
            return new StubDrive();
        });
        device.connect();
        device.invalidateConnection();
        var fresh = new PendingCloseDrive();
        device.parkPendingClose(fresh);

        assertThat(device.closeDriveHandle()).isEqualTo(FrequencyInverterDevice.HandleClose.PENDING);
        assertThatThrownBy(device::connect).isInstanceOf(DriveUnavailableException.class);
        fresh.transferEnded = true;
        device.connect();

        assertThat(opens).hasValue(2);
        assertThat(fresh.isClosed()).isTrue();
        device.disconnect();
    }

    @Test
    void closeDriveHandleReportsFailedOnAnyOtherThrowAndTheNextOpenDoesNotWait() {
        var opens = new AtomicInteger();
        var device = new FrequencyInverterDevice(() -> opens.incrementAndGet() == 1
                ? new StubDrive() {
                    @Override
                    public void close() {
                        super.close();
                        throw new IllegalStateException("simulated drive close failed");
                    }
                }
                : new StubDrive());
        device.connect();

        assertThat(device.closeDriveHandle()).isEqualTo(FrequencyInverterDevice.HandleClose.FAILED);
        assertThat(device.closeDriveHandle()).as("nothing left").isEqualTo(FrequencyInverterDevice.HandleClose.NONE);
        device.invalidateConnection();
        device.connect();
        assertThat(opens).hasValue(2);
        device.disconnect();
    }

    @Test
    void invalidateConnectionClosesTheHandleAndMakesTheNextConnectReopen() {
        var opens = new AtomicInteger();
        List<StubDrive> handles = new CopyOnWriteArrayList<>();
        var device = new FrequencyInverterDevice(() -> {
            opens.incrementAndGet();
            var handle = new StubDrive();
            handles.add(handle);
            return handle;
        });
        device.connect();
        device.connect();

        device.invalidateConnection();

        assertThat(device.isDriveHandleOpen()).isFalse();
        assertThat(handles.getFirst().isClosed()).isTrue();
        assertThat(device.isConnected()).isFalse();
        device.connect();
        assertThat(opens).hasValue(2);
        device.disconnect();
    }

    // ---- the bounded lifecycle-lock wait of runExclusive (BL-212) ------------------------------

    /**
     * A {@code connect()} parked inside {@link ch.rupfizupfi.deck.device.api.DriveProvider#open()},
     * which the contract does not bound, holds the lifecycle lock: runExclusive gives up at its bound
     * naming that holder, its action never runs, and the connect then opens the only session.
     */
    @Test
    @Timeout(value = 20, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void runExclusiveGivesUpWithinTheBoundWhileAConnectIsInsideOpen() throws InterruptedException {
        var opens = new AtomicInteger();
        var inOpen = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var device = new FrequencyInverterDevice(() -> {
            opens.incrementAndGet();
            inOpen.countDown();
            try {
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return new StubDrive();
        });
        Thread connector = Thread.ofPlatform().name("dashboard-connect").start(device::connect);
        assertThat(inOpen.await(5, TimeUnit.SECONDS)).isTrue();
        var ran = new AtomicBoolean();

        long started = System.nanoTime();
        assertThatThrownBy(() -> device.runExclusive(() -> ran.set(true)))
                .isInstanceOf(LifecycleLockTimeoutException.class)
                .hasMessageContaining("lifecycle lock")
                .hasMessageContaining(FrequencyInverterDevice.RUN_EXCLUSIVE_WAIT_MS + " ms")
                .hasMessageContaining("connect()")
                .hasMessageContaining("dashboard-connect");
        long tookMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        release.countDown();
        connector.join(5_000);

        assertThat(tookMs).as("given up at the lock bound, not when open() returns")
                .isGreaterThanOrEqualTo(FrequencyInverterDevice.RUN_EXCLUSIVE_WAIT_MS - 1)
                .isLessThan(FrequencyInverterDevice.RUN_EXCLUSIVE_WAIT_MS + 400);
        assertThat(ran).as("nothing runs outside the lock").isFalse();
        assertThat(connector.isAlive()).isFalse();
        assertThat(opens).as("one session, the connect's").hasValue(1);
        assertThat(device.isDriveHandleOpen()).isTrue();
        device.disconnect();
    }

    /** An interrupt does not cut the bounded wait short, and survives it. */
    @Test
    @Timeout(value = 20, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void runExclusiveWaitsOutItsBoundThroughAnInterruptAndKeepsTheFlag() throws InterruptedException {
        var inOpen = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var device = new FrequencyInverterDevice(() -> {
            inOpen.countDown();
            try {
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return new StubDrive();
        });
        Thread connector = Thread.ofPlatform().name("dashboard-connect").start(device::connect);
        assertThat(inOpen.await(5, TimeUnit.SECONDS)).isTrue();

        Thread.currentThread().interrupt();
        long started = System.nanoTime();
        assertThatThrownBy(() -> device.runExclusive(() -> {
        }, 300)).isInstanceOf(LifecycleLockTimeoutException.class);
        long tookMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        boolean stillInterrupted = Thread.interrupted();
        release.countDown();
        connector.join(5_000);

        assertThat(tookMs).isGreaterThanOrEqualTo(299);
        assertThat(stillInterrupted).as("the interrupt flag is restored").isTrue();
        device.disconnect();
    }

    /**
     * A {@code disconnect()} inside a handle close wedged at the contract's bound holds the lock for
     * that close only: runExclusive runs once it ends, inside its own bound, and finds the handle gone.
     */
    @Test
    @Timeout(value = 20, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void runExclusiveRunsOnceADisconnectInsideAWedgedCloseReleasesTheLock() throws InterruptedException {
        var inClose = new CountDownLatch(1);
        var device = new FrequencyInverterDevice(() -> new StubDrive() {
            @Override
            public void close() {
                inClose.countDown();
                try {
                    Thread.sleep(DriveLane.STOP_DEADLINE_MILLIS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                super.close();
            }
        });
        device.connect();
        Thread disconnector = Thread.ofPlatform().name("run-disconnect").start(device::disconnect);
        assertThat(inClose.await(5, TimeUnit.SECONDS)).isTrue();
        var closed = new AtomicReference<FrequencyInverterDevice.HandleClose>();

        long started = System.nanoTime();
        device.runExclusive(() -> closed.set(device.closeDriveHandle()));
        long tookMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        disconnector.join(5_000);

        assertThat(tookMs).isLessThan(FrequencyInverterDevice.RUN_EXCLUSIVE_WAIT_MS);
        assertThat(closed).as("the disconnect dropped the handle").hasValue(FrequencyInverterDevice.HandleClose.NONE);
        assertThat(disconnector.isAlive()).isFalse();
    }

    /**
     * The poll-thread join of {@code unregisterObserver()} holds the instance monitor up to 2000 ms,
     * so it holds the lifecycle lock too: runExclusive's bound covers it, rather than getting the
     * lock at once and then waiting on the monitor without one.
     */
    @Test
    @Timeout(value = 20, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void unregisterObserverHoldsTheLifecycleLockAcrossThePollThreadJoin() throws InterruptedException {
        var drive = new BlockingFirstReadDrive();
        var device = new FrequencyInverterDevice(() -> drive);
        InfoObserver observer = info -> {
        };
        device.connect();
        Thread unsubscriber = null;
        try {
            device.registerObserver(observer);
            Thread poll = drive.awaitFirstCaller();
            unsubscriber = Thread.ofPlatform().name("dashboard-unsubscribe")
                    .start(() -> device.unregisterObserver(observer));
            long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (unsubscriber.getState() != Thread.State.TIMED_WAITING && System.nanoTime() < until) {
                Thread.onSpinWait();
            }
            assertThat(poll.isAlive()).as("the poll is still inside its read").isTrue();
            var ran = new AtomicBoolean();

            assertThatThrownBy(() -> device.runExclusive(() -> ran.set(true), 300))
                    .isInstanceOf(LifecycleLockTimeoutException.class)
                    .hasMessageContaining("unregisterObserver()")
                    .hasMessageContaining("dashboard-unsubscribe");
            assertThat(ran).isFalse();
        } finally {
            drive.release();
            if (unsubscriber != null) {
                unsubscriber.join(5_000);
            }
            device.disconnect();
        }
    }

    /**
     * BL-121: the deck holds no lock around a drive call, so a stop issued while the poll is inside a
     * slow read reaches the drive at once. Whether the drive takes it before that read ends is the
     * driver's gate, not the deck's.
     */
    @Test
    @Timeout(20)
    void aStopDuringASlowPollIsNotDelayedByTheDeck() throws InterruptedException {
        var drive = new BlockingFirstReadDrive();
        var device = new FrequencyInverterDevice(() -> drive);
        InfoObserver observer = info -> {
        };
        device.connect();
        try {
            device.registerObserver(observer);
            Thread poll = drive.awaitFirstCaller();

            long started = System.nanoTime();
            device.withDrive(Drive::stop);
            long tookMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

            assertThat(poll.isAlive()).as("the poll is still inside its read").isTrue();
            assertThat(drive.stops()).isEqualTo(1);
            assertThat(tookMs).as("the stop waited for nothing in the deck").isLessThan(200);
        } finally {
            drive.release();
            device.unregisterObserver(observer);
            device.disconnect();
        }
    }

    /**
     * BL-122: on the simulator's gate the poll's reads queue in the STATUS lane behind other traffic,
     * so frames keep coming under continuous setpoint writes.
     */
    @Test
    @Timeout(30)
    void thePollKeepsDeliveringFramesUnderContinuousTraffic() throws InterruptedException {
        var properties = new SimulatedBenchProperties();
        properties.setTransactionLatencyMillis(100);
        var faults = new SimulatedFaultSwitches();
        var device = new FrequencyInverterDevice(
                new SimulatedDriveProvider(new SimulatedBench(properties, faults), faults));
        var frames = new CountDownLatch(2);
        InfoObserver observer = info -> frames.countDown();
        var traffic = new AtomicBoolean(true);
        device.connect();
        Thread run = Thread.ofPlatform().name("run-traffic").start(() -> {
            while (traffic.get()) {
                device.withDrive(drive -> drive.setSpeedReferenceValueAsRpm(100));
            }
        });
        try {
            device.registerObserver(observer);

            assertThat(frames.await(10, TimeUnit.SECONDS)).as("two frames polled under continuous traffic").isTrue();
            assertThat(run.isAlive()).as("the setpoint traffic ran throughout").isTrue();
        } finally {
            traffic.set(false);
            run.join(5_000);
            device.unregisterObserver(observer);
            device.disconnect();
        }
    }

    /**
     * BL-122: a stop issued while the poll's motor-data read is in flight on the simulator's gate
     * waits for that one transaction, then goes out ahead of the poll's control-word read. Nothing
     * else is on the bus, so the stop's own time tells the cases apart: one transaction had it not
     * overlapped a poll read, about two when it overlapped the read and went next, about three had
     * the control-word read gone first. The lower bound is what proves the overlap.
     */
    @Test
    @Timeout(30)
    void aStopDuringAPollReadWaitsForThatOneTransactionAndGoesAheadOfTheNextRead()
            throws InterruptedException {
        long latencyMs = 300;
        var properties = new SimulatedBenchProperties();
        properties.setTransactionLatencyMillis(latencyMs);
        var faults = new SimulatedFaultSwitches();
        var simulated = new SimulatedDriveProvider(new SimulatedBench(properties, faults), faults);
        var motorDataDispatched = new AtomicReference<CountDownLatch>();
        var device = new FrequencyInverterDevice(() -> new SignallingDrive(simulated.open(), motorDataDispatched));
        InfoObserver observer = info -> {
        };
        device.connect();
        try {
            List<Long> stopMillis = new CopyOnWriteArrayList<>();
            for (int i = 0; i < 4; i++) {
                var dispatched = new CountDownLatch(1);
                motorDataDispatched.set(dispatched);
                if (i == 0) {
                    device.registerObserver(observer);
                }
                assertThat(dispatched.await(5, TimeUnit.SECONDS)).as("the poll's motor-data read started").isTrue();
                // The signal fires as the read enters the handle, a hand-off ahead of its transaction.
                Thread.sleep(SETTLE_MS);

                long started = System.nanoTime();
                device.withDrive(Drive::stop);
                stopMillis.add(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
            }

            assertThat(stopMillis).allSatisfy(ms -> assertThat(ms)
                    // It waited behind the read in flight: the stop overlapped a poll read.
                    .isGreaterThan(latencyMs + latencyMs / 4)
                    // That read plus its own, under one transaction of slack: the next read did not go first.
                    .isLessThan(2 * latencyMs + latencyMs / 2));
        } finally {
            device.unregisterObserver(observer);
            device.disconnect();
        }
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
        private final AtomicInteger stops = new AtomicInteger();

        @Override
        public void stop() {
            stops.incrementAndGet();
        }

        int stops() {
            return stops.get();
        }

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

    /**
     * Forwards every call to a gated handle, and counts down the latch armed in {@code dispatched}
     * as a motor-data read enters it. Only the poll reads motor data, and nothing else is in flight
     * when it does, so that read's transaction starts as soon as the gate hands it to its I/O thread.
     */
    private record SignallingDrive(Drive delegate, AtomicReference<CountDownLatch> dispatched) implements Drive {
        @Override
        public Map<String, Integer> getMotorData() {
            var armed = dispatched.getAndSet(null);
            if (armed != null) {
                armed.countDown();
            }
            return delegate.getMotorData();
        }

        @Override
        public Map<String, Boolean> getControlParameters() {
            return delegate.getControlParameters();
        }

        @Override
        public ControlSource readControlSource() {
            return delegate.readControlSource();
        }

        @Override
        public void setControlParameters(Boolean start, Boolean generalEnable, Boolean directionIsForward,
                                         Boolean localRemote, Boolean useSecondRamp) {
            delegate.setControlParameters(start, generalEnable, directionIsForward, localRemote, useSecondRamp);
        }

        @Override
        public void setStart(boolean start) {
            delegate.setStart(start);
        }

        @Override
        public void setGeneralEnable(boolean generalEnable) {
            delegate.setGeneralEnable(generalEnable);
        }

        @Override
        public void setDirection(boolean directionIsForward) {
            delegate.setDirection(directionIsForward);
        }

        @Override
        public boolean getDirection() {
            return delegate.getDirection();
        }

        @Override
        public void setUseSecondRamp(boolean useSecondRamp) {
            delegate.setUseSecondRamp(useSecondRamp);
        }

        @Override
        public void setSecondSpeedRampTime(int accelerationRampTime, int decelerationRampTime) {
            delegate.setSecondSpeedRampTime(accelerationRampTime, decelerationRampTime);
        }

        @Override
        public void setSpeedReferenceValueAsRpm(int rpm) {
            delegate.setSpeedReferenceValueAsRpm(rpm);
        }

        @Override
        public int getMotorSpeedValueAsRpm() {
            return delegate.getMotorSpeedValueAsRpm();
        }

        @Override
        public void setActionInCaseOfCommunicationError(int action) {
            delegate.setActionInCaseOfCommunicationError(action);
        }

        @Override
        public void enableLinkLossStop(Duration timeout) {
            delegate.enableLinkLossStop(timeout);
        }

        @Override
        public void stop() {
            delegate.stop();
        }

        @Override
        public DriveGating gating() {
            return delegate.gating();
        }

        @Override
        public long stopEpoch() {
            return delegate.stopEpoch();
        }

        @Override
        public void runLinked(long expectedEpoch, Consumer<Drive> body) {
            delegate.runLinked(expectedEpoch, body);
        }

        @Override
        public DriveAsync async() {
            return delegate.async();
        }

        @Override
        public void close() {
            delegate.close();
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

    /**
     * A gated handle whose transfer outlasts the close bound: {@code close()} reports it pending
     * until {@link #transferEnded}, then closes.
     */
    private static class PendingCloseDrive extends StubDrive {
        final AtomicInteger closeAttempts = new AtomicInteger();
        volatile boolean transferEnded;

        @Override
        public void close() {
            closeAttempts.incrementAndGet();
            if (!transferEnded) {
                throw new DriveClosePendingException("transfer still in flight (test)");
            }
            super.close();
        }
    }

    /** Throws a checked exception from a method that declares none, as the Kotlin driver does. */
    @SuppressWarnings("unchecked")
    private static <T extends Throwable> RuntimeException sneaky(Throwable t) throws T {
        throw (T) t;
    }
}
