package ch.rupfizupfi.deck.device.api.support;

import ch.rupfizupfi.deck.device.api.ControlSource;
import ch.rupfizupfi.deck.device.api.Drive;
import ch.rupfizupfi.deck.device.api.DriveAsync;
import ch.rupfizupfi.deck.device.api.DriveClosePendingException;
import ch.rupfizupfi.deck.device.api.DriveCommunicationException;
import ch.rupfizupfi.deck.device.api.DriveDeadlineException;
import ch.rupfizupfi.deck.device.api.DriveGating;
import ch.rupfizupfi.deck.device.api.DriveLane;
import ch.rupfizupfi.deck.device.api.StaleStopEpochException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.entry;

/** {@link GatedDrive}'s lane table, exception transparency and lifecycle over recording operations. */
@Timeout(value = 30, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class GatedDriveTest {

    private final RecordingOperations operations = new RecordingOperations();
    private GatedDrive drive;

    @AfterEach
    void close() {
        if (drive != null) {
            operations.latencyMillis = 0;
            try {
                drive.close();
            } catch (RuntimeException ignored) {
                // a case may have closed it already
            }
        }
    }

    private GatedDrive open(LaneDeadlines deadlines) {
        drive = new GatedDrive(operations, "gated-test-io", deadlines);
        return drive;
    }

    private GatedDrive open() {
        return open(LaneDeadlines.of(Duration.ofSeconds(2), Duration.ofSeconds(2), Duration.ofSeconds(2),
                Duration.ofSeconds(2)));
    }

    // ---- lanes ------------------------------------------------------------------------------

    /**
     * One row per call shape: the lane its operation implies, the sync call and its async twin. Both
     * views are checked against the same rows, so a slip in either copy of the lane mapping fails.
     */
    private record LaneCase(String method, String shape, DriveLane lane, Consumer<Drive> sync,
                            Function<DriveAsync, CompletableFuture<?>> async) {
    }

    private static final List<LaneCase> LANE_CASES = List.of(
            new LaneCase("stop", "()", DriveLane.STOP, Drive::stop, DriveAsync::stop),
            new LaneCase("setControlParameters", "(false, false, ...)", DriveLane.STOP,
                    d -> d.setControlParameters(false, false, null, null, null),
                    a -> a.setControlParameters(false, false, null, null, null)),
            new LaneCase("setControlParameters", "(false, null, ...)", DriveLane.STOP,
                    d -> d.setControlParameters(false, null, null, null, null),
                    a -> a.setControlParameters(false, null, null, null, null)),
            new LaneCase("setControlParameters", "(null, false, ...)", DriveLane.STOP,
                    d -> d.setControlParameters(null, false, null, null, null),
                    a -> a.setControlParameters(null, false, null, null, null)),
            new LaneCase("setGeneralEnable", "(false)", DriveLane.STOP,
                    d -> d.setGeneralEnable(false), a -> a.setGeneralEnable(false)),
            new LaneCase("setStart", "(false)", DriveLane.STOP, d -> d.setStart(false), a -> a.setStart(false)),
            new LaneCase("setSpeedReferenceValueAsRpm", "(0)", DriveLane.STOP,
                    d -> d.setSpeedReferenceValueAsRpm(0), a -> a.setSpeedReferenceValueAsRpm(0)),
            new LaneCase("setControlParameters", "(true, true, ...)", DriveLane.CONTROL,
                    d -> d.setControlParameters(true, true, null, null, null),
                    a -> a.setControlParameters(true, true, null, null, null)),
            new LaneCase("setControlParameters", "(false, true, ...)", DriveLane.CONTROL,
                    d -> d.setControlParameters(false, true, null, null, null),
                    a -> a.setControlParameters(false, true, null, null, null)),
            new LaneCase("setControlParameters", "(direction only)", DriveLane.CONTROL,
                    d -> d.setControlParameters(null, null, true, null, null),
                    a -> a.setControlParameters(null, null, true, null, null)),
            new LaneCase("setControlParameters", "(false, false, direction)", DriveLane.CONTROL,
                    d -> d.setControlParameters(false, false, true, null, null),
                    a -> a.setControlParameters(false, false, true, null, null)),
            new LaneCase("setControlParameters", "(false, null, LOC/REM)", DriveLane.CONTROL,
                    d -> d.setControlParameters(false, null, null, true, null),
                    a -> a.setControlParameters(false, null, null, true, null)),
            new LaneCase("setControlParameters", "(null, false, ramp)", DriveLane.CONTROL,
                    d -> d.setControlParameters(null, false, null, null, true),
                    a -> a.setControlParameters(null, false, null, null, true)),
            new LaneCase("setStart", "(true)", DriveLane.CONTROL, d -> d.setStart(true), a -> a.setStart(true)),
            new LaneCase("setGeneralEnable", "(true)", DriveLane.CONTROL,
                    d -> d.setGeneralEnable(true), a -> a.setGeneralEnable(true)),
            new LaneCase("setDirection", "(true)", DriveLane.CONTROL,
                    d -> d.setDirection(true), a -> a.setDirection(true)),
            new LaneCase("setUseSecondRamp", "(true)", DriveLane.CONTROL,
                    d -> d.setUseSecondRamp(true), a -> a.setUseSecondRamp(true)),
            new LaneCase("setSpeedReferenceValueAsRpm", "(300)", DriveLane.SETPOINT,
                    d -> d.setSpeedReferenceValueAsRpm(300), a -> a.setSpeedReferenceValueAsRpm(300)),
            new LaneCase("setSecondSpeedRampTime", "(10, 10)", DriveLane.SETPOINT,
                    d -> d.setSecondSpeedRampTime(10, 10), a -> a.setSecondSpeedRampTime(10, 10)),
            new LaneCase("setActionInCaseOfCommunicationError", "(2)", DriveLane.CONFIG,
                    d -> d.setActionInCaseOfCommunicationError(2), a -> a.setActionInCaseOfCommunicationError(2)),
            new LaneCase("enableLinkLossStop", "(1 s)", DriveLane.CONFIG,
                    d -> d.enableLinkLossStop(Duration.ofSeconds(1)),
                    a -> a.enableLinkLossStop(Duration.ofSeconds(1))),
            new LaneCase("getControlParameters", "()", DriveLane.STATUS,
                    Drive::getControlParameters, DriveAsync::getControlParameters),
            new LaneCase("readControlSource", "()", DriveLane.STATUS,
                    Drive::readControlSource, DriveAsync::readControlSource),
            new LaneCase("getDirection", "()", DriveLane.STATUS, Drive::getDirection, DriveAsync::getDirection),
            new LaneCase("getMotorSpeedValueAsRpm", "()", DriveLane.STATUS,
                    Drive::getMotorSpeedValueAsRpm, DriveAsync::getMotorSpeedValueAsRpm),
            new LaneCase("getMotorData", "()", DriveLane.STATUS, Drive::getMotorData, DriveAsync::getMotorData));

    /** Every lane gets its own short deadline and every operation outlasts it. */
    private void openWithOutlastedDeadlines() {
        open(new LaneDeadlines(Duration.ofMillis(60), Duration.ofMillis(61), Duration.ofMillis(62),
                Duration.ofMillis(63), Duration.ofMillis(64)));
        operations.latencyMillis = 400;
    }

    /**
     * The deadline exception names the lane each call ran in. In table order, one at a time, so a
     * stop never overtakes a CONTROL or SETPOINT call and fails it as stale instead.
     */
    @Test
    void eachCallRunsInTheLaneItsOperationImplies() {
        openWithOutlastedDeadlines();

        for (LaneCase c : LANE_CASES) {
            String name = c.method() + c.shape();
            assertThatThrownBy(() -> c.sync().accept(drive)).as(name)
                    .isInstanceOf(DriveDeadlineException.class)
                    .satisfies(e -> assertThat(((DriveDeadlineException) e).lane()).as(name).isEqualTo(c.lane()));
        }
    }

    /** The async view keeps its own copy of the lane mapping; it must agree with the sync one. */
    @Test
    void eachAsyncCallRunsInTheSameLaneAsItsSyncTwin() {
        openWithOutlastedDeadlines();

        for (LaneCase c : LANE_CASES) {
            String name = "async " + c.method() + c.shape();
            CompletableFuture<?> future = c.async().apply(drive.async());
            assertThatThrownBy(future::join).as(name)
                    .isInstanceOf(CompletionException.class)
                    .cause().isInstanceOf(DriveDeadlineException.class)
                    .satisfies(e -> assertThat(((DriveDeadlineException) e).lane()).as(name).isEqualTo(c.lane()));
        }
    }

    @Test
    void theLaneTableCoversEveryAsyncMethod() {
        Set<String> declared = Arrays.stream(DriveAsync.class.getDeclaredMethods())
                .filter(m -> !m.isSynthetic() && !Modifier.isStatic(m.getModifiers()))
                .map(Method::getName)
                .collect(Collectors.toSet());

        assertThat(LANE_CASES.stream().map(LaneCase::method).collect(Collectors.toSet()))
                .isEqualTo(declared);
    }

    /**
     * The contract's {@code stop()} fallback, run over this handle, sends the one-write stop and zero
     * speed, both in the STOP lane: each bumps the epoch once.
     */
    @Test
    void theStopFallbackOverAGatedHandleStaysInTheStopLane() {
        open();
        Drive viaDefault = new Drive() {
            // Only the two calls the fallback makes; anything else is not under test.
            @Override
            public void setControlParameters(Boolean s, Boolean g, Boolean d, Boolean l, Boolean r) {
                drive.setControlParameters(s, g, d, l, r);
            }

            @Override
            public void setSpeedReferenceValueAsRpm(int rpm) {
                drive.setSpeedReferenceValueAsRpm(rpm);
            }

            @Override public Map<String, Boolean> getControlParameters() { throw new UnsupportedOperationException(); }
            @Override public void setStart(boolean start) { throw new UnsupportedOperationException(); }
            @Override public void setGeneralEnable(boolean generalEnable) { throw new UnsupportedOperationException(); }
            @Override public void setDirection(boolean directionIsForward) { throw new UnsupportedOperationException(); }
            @Override public boolean getDirection() { throw new UnsupportedOperationException(); }
            @Override public void setUseSecondRamp(boolean useSecondRamp) { throw new UnsupportedOperationException(); }
            @Override public void setSecondSpeedRampTime(int a, int d) { throw new UnsupportedOperationException(); }
            @Override public int getMotorSpeedValueAsRpm() { throw new UnsupportedOperationException(); }
            @Override public Map<String, Integer> getMotorData() { throw new UnsupportedOperationException(); }
            @Override public void setActionInCaseOfCommunicationError(int action) { throw new UnsupportedOperationException(); }
            @Override public void close() { }
        };

        viaDefault.stop();

        assertThat(operations.calls).containsExactly("setControlParameters(false, false, null, null, null)",
                "setSpeedReferenceValueAsRpm(0)");
        assertThat(drive.stopEpoch()).as("two STOP-lane submissions").isEqualTo(2);
    }

    @Test
    void stopIsOneStopLaneOperationAndBumpsTheEpochOnce() {
        open();

        drive.stop();

        assertThat(operations.calls).containsExactly("stop");
        assertThat(drive.stopEpoch()).isEqualTo(1);
        assertThat(drive.gating()).isEqualTo(DriveGating.DRIVER_SERIALIZES);
    }

    // ---- exception transparency -------------------------------------------------------------

    @Test
    void anUncheckedFailureKeepsItsTypeThroughTheJoin() {
        open();
        operations.failure = new IllegalArgumentException("ramp too long");
        assertThatThrownBy(() -> drive.setSecondSpeedRampTime(99_999, 0))
                .isExactlyInstanceOf(IllegalArgumentException.class);

        operations.failure = new DriveCommunicationException("no reply");
        assertThatThrownBy(drive::getMotorData).isExactlyInstanceOf(DriveCommunicationException.class);

        operations.failure = new IllegalStateException("did not read back");
        assertThatThrownBy(() -> drive.enableLinkLossStop(Duration.ofSeconds(1)))
                .isExactlyInstanceOf(IllegalStateException.class);
    }

    /** As Kotlin lets the vendor's checked {@code NegativeConfirmationException} through undeclared. */
    @Test
    void aCheckedFailureBecomesADriveCommunicationException() {
        open();
        operations.failure = new Exception("negative confirmation");

        assertThatThrownBy(drive::getMotorSpeedValueAsRpm)
                .isExactlyInstanceOf(DriveCommunicationException.class)
                .hasCauseInstanceOf(Exception.class)
                .hasRootCauseMessage("negative confirmation");
    }

    // ---- groups -----------------------------------------------------------------------------

    @Test
    void aGroupRunsThroughTheDriveItIsGiven() {
        open();

        drive.runLinked(drive.stopEpoch(), d -> {
            d.setSpeedReferenceValueAsRpm(300);
            d.setControlParameters(true, true, null, null, null);
        });

        assertThat(operations.calls).containsExactly("setSpeedReferenceValueAsRpm(300)",
                "setControlParameters(true, true, null, null, null)");
    }

    @Test
    void insideAGroupTheHandleItselfAndTheGroupOnlyCallsAreRefused() {
        open();

        drive.runLinked(drive.stopEpoch(), d -> {
            assertThatThrownBy(drive::getMotorData).as("the handle itself").isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> d.runLinked(0, x -> {
            })).as("a nested group").isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(d::async).as("async").isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(d::close).as("close").isInstanceOf(IllegalStateException.class);
        });

        assertThat(operations.calls).isEmpty();
    }

    @Test
    void aStopOnTheHandleFromInsideAGroupGoesOutAndCutsItShort() {
        open();

        assertThatThrownBy(() -> drive.runLinked(drive.stopEpoch(), d -> {
            d.setSpeedReferenceValueAsRpm(300);
            drive.stop();
            drive.setGeneralEnable(false);
            drive.async().setSpeedReferenceValueAsRpm(0).join();
            d.setControlParameters(true, true, null, null, null);
        })).isInstanceOf(StaleStopEpochException.class);

        assertThat(operations.calls).containsExactly("setSpeedReferenceValueAsRpm(300)", "stop",
                "setGeneralEnable(false)", "setSpeedReferenceValueAsRpm(0)");
    }

    /**
     * A STOP-lane call on the drive handed to the body is a stop like any other: it goes out, cuts
     * the group, and the body's next call is stale. {@code MotorSafetyController#energize} hands a
     * {@code Consumer<Drive>} that starts with zero speed, so this is the energize sequence's shape.
     */
    @Test
    void aStopLaneCallOnTheGroupsOwnDriveCutsTheGroup() {
        open();

        assertThatThrownBy(() -> drive.runLinked(drive.stopEpoch(), d -> {
            d.setSpeedReferenceValueAsRpm(0);
            d.setControlParameters(true, true, null, null, null);
        })).isInstanceOf(StaleStopEpochException.class);

        assertThat(operations.calls).containsExactly("setSpeedReferenceValueAsRpm(0)");
        assertThat(drive.stopEpoch()).isEqualTo(1);
    }

    /**
     * A stop from the body cuts the group, but the body still runs: from then on the handle must
     * refuse it everything but a stop, or an energizing write stamped with the new epoch would go
     * out after the stop.
     */
    @Test
    void afterAStopCutTheGroupTheBodyStillCannotEnergizeThroughTheHandle() {
        open();

        drive.runLinked(drive.stopEpoch(), d -> {
            d.setSpeedReferenceValueAsRpm(300);
            drive.stop();
            assertRefusedOutsideTheGroup(d);
        });

        assertThat(operations.calls).containsExactly("setSpeedReferenceValueAsRpm(300)", "stop",
                "setStart(false)", "setSpeedReferenceValueAsRpm(0)");
    }

    /** The same for a body that held the group past the CONTROL deadline and lost it. */
    @Test
    void afterTheGroupWasAbandonedTheBodyStillCannotEnergizeThroughTheHandle() {
        open(LaneDeadlines.of(Duration.ofMillis(100), Duration.ofSeconds(2), Duration.ofSeconds(2),
                Duration.ofSeconds(2)));

        drive.runLinked(drive.stopEpoch(), d -> {
            d.setSpeedReferenceValueAsRpm(300);
            sleep(400);
            assertThatThrownBy(() -> d.setControlParameters(true, true, null, null, null))
                    .as("the abandoned group's own drive").isInstanceOf(IllegalStateException.class);
            assertRefusedOutsideTheGroup(d);
        });

        assertThat(operations.calls).containsExactly("setSpeedReferenceValueAsRpm(300)",
                "setStart(false)", "setSpeedReferenceValueAsRpm(0)");
    }

    /**
     * From a body whose group is no longer active: every non-STOP call on the handle, sync or
     * async, and a nested group are refused before they reach the operations; STOP-lane calls on
     * the handle still go out.
     */
    private void assertRefusedOutsideTheGroup(Drive groupDrive) {
        assertThatThrownBy(() -> drive.setControlParameters(true, true, null, null, null))
                .as("an energizing write on the handle").isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> drive.setSpeedReferenceValueAsRpm(300))
                .as("a setpoint on the handle").isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(drive::getMotorData)
                .as("a read on the handle").isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> drive.async().setStart(true))
                .as("an energizing write on the async view").isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> drive.async().setControlParameters(true, true, null, null, null))
                .as("an energizing control word on the async view").isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> drive.runLinked(drive.stopEpoch(), inner -> inner.setStart(true)))
                .as("a nested group on the handle").isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> groupDrive.runLinked(drive.stopEpoch(), inner -> inner.setStart(true)))
                .as("a nested group on the group's drive").isInstanceOf(IllegalStateException.class);
        drive.setStart(false);
        drive.async().setSpeedReferenceValueAsRpm(0).join();
    }

    // ---- lifecycle ----------------------------------------------------------------------------

    @Test
    void closeClosesThePortOnce() {
        open();

        drive.close();
        drive.close();

        assertThat(operations.closes).isEqualTo(1);
    }

    /**
     * A transaction in flight that ends within the STOP deadline: close() waits for it and the stop
     * queued behind it and returns with the port closed, so a fresh handle opened after it never
     * overlaps this one's session.
     */
    @Test
    void closeWithinTheStopDeadlineReturnsWithThePortClosed() {
        open(new LaneDeadlines(Duration.ofMillis(400), Duration.ofSeconds(2), Duration.ofSeconds(2),
                Duration.ofSeconds(2), Duration.ofSeconds(2)));
        operations.latencyMillis = 50;
        drive.async().getMotorData();
        sleep(10);
        var stop = drive.async().stop();
        var concurrentCloser = Thread.ofPlatform().start(drive::close);

        drive.close();

        assertThat(operations.calls).containsExactly("getMotorData", "stop", "close");
        assertThat(operations.closes).isEqualTo(1);
        assertThat(operations.closeThread).isEqualTo(drive.ioThreadName());
        stop.join();
        join(concurrentCloser);
        assertThat(operations.closes).as("closed once for both closers").isEqualTo(1);
    }

    /**
     * BL-135/BL-148: a transaction wedged past the STOP deadline. Both closers give up at the bound
     * with the port still open - neither may report it closed while the transfer is in flight - and
     * the I/O thread closes it once the wedge ends, after the stop queued behind it went out.
     */
    @Test
    void closePastTheStopDeadlineThrowsPendingAndThePortClosesOnTheIoThreadAfterTheWedge() {
        open(new LaneDeadlines(Duration.ofMillis(100), Duration.ofMillis(100), Duration.ofMillis(100),
                Duration.ofMillis(100), Duration.ofMillis(100)));
        operations.latencyMillis = 800;
        drive.async().getMotorData();
        sleep(30);
        assertThatThrownBy(drive::stop).isInstanceOf(DriveDeadlineException.class);
        var concurrent = new CompletableFuture<Throwable>();
        Thread.ofPlatform().start(() -> {
            try {
                drive.close();
                concurrent.complete(null);
            } catch (Throwable t) {
                concurrent.complete(t);
            }
        });

        long started = System.nanoTime();
        assertThatThrownBy(drive::close).isInstanceOf(DriveClosePendingException.class);
        assertThat(Duration.ofNanos(System.nanoTime() - started)).as("bounded by the STOP deadline")
                .isLessThan(Duration.ofMillis(100 + 250));
        assertThat(concurrent.join()).as("the concurrent closer").isInstanceOf(DriveClosePendingException.class);
        assertThat(operations.closes).as("the port is still open while the transfer is in flight").isZero();
        assertThatThrownBy(drive::getMotorData).as("closed to callers").isInstanceOf(IllegalStateException.class);

        awaitClosed();
        assertThat(operations.calls).containsExactly("getMotorData", "stop", "close");
        assertThat(operations.closes).isEqualTo(1);
        assertThat(operations.closeThread).isEqualTo(drive.ioThreadName());
        assertThatCode(drive::close).as("a close after the wedge").doesNotThrowAnyException();
        assertThat(operations.closes).isEqualTo(1);
    }

    /** What the provider's port close throws reaches the closer, as before it ran on the I/O thread. */
    @Test
    void aFailingPortCloseReachesTheCloser() {
        open();
        operations.closeFailure = new IllegalStateException("port close failed");

        assertThatThrownBy(drive::close).isExactlyInstanceOf(IllegalStateException.class)
                .hasMessage("port close failed");
        assertThatCode(drive::close).as("idempotent").doesNotThrowAnyException();
        assertThat(operations.closes).isEqualTo(1);
    }

    private void awaitClosed() {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (operations.closes == 0 && System.nanoTime() < deadline) {
            sleep(10);
        }
    }

    @Test
    void closingTheHandleFromInsideAGroupIsRefused() {
        open();

        drive.runLinked(drive.stopEpoch(), d -> {
            assertThatThrownBy(drive::close).isInstanceOf(IllegalStateException.class);
            d.setSpeedReferenceValueAsRpm(300);
            drive.stop();
            assertThatThrownBy(drive::close).as("after a stop cut the group")
                    .isInstanceOf(IllegalStateException.class);
        });

        assertThat(operations.closes).isZero();
        assertThat(operations.calls).containsExactly("setSpeedReferenceValueAsRpm(300)", "stop");
    }

    @Test
    void theAsyncViewIsTheSameOperationWithoutTheJoin() {
        open();

        assertThat(drive.async().getMotorData().join()).containsExactly(entry("speed", 7));
        drive.async().setSpeedReferenceValueAsRpm(300).join();

        assertThat(operations.calls).containsExactly("getMotorData", "setSpeedReferenceValueAsRpm(300)");
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private static void join(Thread thread) {
        try {
            thread.join(Duration.ofSeconds(5));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        assertThat(thread.isAlive()).as(thread.getName() + " finished").isFalse();
    }

    /** Records every call; sleeps {@link #latencyMillis} and throws {@link #failure} if set. */
    private static final class RecordingOperations implements DriveOperations {

        final List<String> calls = new CopyOnWriteArrayList<>();
        volatile long latencyMillis;
        volatile Throwable failure;
        volatile RuntimeException closeFailure;
        volatile int closes;
        volatile String closeThread;

        private void transact(String call) {
            if (latencyMillis > 0) {
                try {
                    Thread.sleep(latencyMillis);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            Throwable f = failure;
            if (f != null) {
                sneakyThrow(f);
            }
            calls.add(call);
        }

        @SuppressWarnings("unchecked")
        private static <E extends Throwable> void sneakyThrow(Throwable t) throws E {
            throw (E) t;
        }

        @Override
        public void stop() {
            transact("stop");
        }

        @Override
        public void setControlParameters(Boolean start, Boolean generalEnable, Boolean directionIsForward,
                                         Boolean localRemote, Boolean useSecondRamp) {
            transact("setControlParameters(" + start + ", " + generalEnable + ", " + directionIsForward + ", "
                    + localRemote + ", " + useSecondRamp + ")");
        }

        @Override
        public Map<String, Boolean> getControlParameters() {
            transact("getControlParameters");
            return Map.of();
        }

        @Override
        public ControlSource readControlSource() {
            transact("readControlSource");
            return ControlSource.REMOTE;
        }

        @Override
        public void setStart(boolean start) {
            transact("setStart(" + start + ")");
        }

        @Override
        public void setGeneralEnable(boolean generalEnable) {
            transact("setGeneralEnable(" + generalEnable + ")");
        }

        @Override
        public void setDirection(boolean directionIsForward) {
            transact("setDirection(" + directionIsForward + ")");
        }

        @Override
        public boolean getDirection() {
            transact("getDirection");
            return true;
        }

        @Override
        public void setUseSecondRamp(boolean useSecondRamp) {
            transact("setUseSecondRamp(" + useSecondRamp + ")");
        }

        @Override
        public void setSecondSpeedRampTime(int accelerationRampTime, int decelerationRampTime) {
            transact("setSecondSpeedRampTime(" + accelerationRampTime + ", " + decelerationRampTime + ")");
        }

        @Override
        public void setSpeedReferenceValueAsRpm(int rpm) {
            transact("setSpeedReferenceValueAsRpm(" + rpm + ")");
        }

        @Override
        public int getMotorSpeedValueAsRpm() {
            transact("getMotorSpeedValueAsRpm");
            return 7;
        }

        @Override
        public Map<String, Integer> getMotorData() {
            transact("getMotorData");
            return Map.of("speed", 7);
        }

        @Override
        public void setActionInCaseOfCommunicationError(int action) {
            transact("setActionInCaseOfCommunicationError(" + action + ")");
        }

        @Override
        public void enableLinkLossStop(Duration timeout) {
            transact("enableLinkLossStop(" + timeout + ")");
        }

        @Override
        public void close() {
            calls.add("close");
            closeThread = Thread.currentThread().getName();
            closes++;
            RuntimeException f = closeFailure;
            if (f != null) {
                throw f;
            }
        }
    }
}
