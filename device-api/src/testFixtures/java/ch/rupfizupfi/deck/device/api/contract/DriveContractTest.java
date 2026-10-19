package ch.rupfizupfi.deck.device.api.contract;

import ch.rupfizupfi.deck.device.api.Drive;
import ch.rupfizupfi.deck.device.api.DriveClosePendingException;
import ch.rupfizupfi.deck.device.api.DriveCommunicationException;
import ch.rupfizupfi.deck.device.api.DriveDeadlineException;
import ch.rupfizupfi.deck.device.api.DriveGating;
import ch.rupfizupfi.deck.device.api.DriveLane;
import ch.rupfizupfi.deck.device.api.StaleStopEpochException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assumptions.assumeThat;

/**
 * The documented behaviour of {@link Drive}, which every provider - the deck's simulator and the
 * CFW11 driver - extends with a {@link Fixture} of its own. Since contract 1.4.0.
 * <p>
 * Hardware-free by default: a fixture backs the drive with a model of its plant (a register map, the
 * simulated bench), which it can put in states a test needs. The {@code bench} tagged cases need a
 * real plant and skip unless {@link Fixture#livePlant()} says there is one.
 * <p>
 * The cases a run's safety leans on: a null in {@link Drive#setControlParameters} leaves that bit
 * (the one-write stop sets start and general enable together and must not touch direction or
 * ramp; I4), and {@link Drive#enableLinkLossStop}'s refusals (a drive that cannot stop itself is
 * never energized; I19).
 * <p>
 * Since 1.5.0 also {@link Drive#stop()} for every provider, and for a
 * {@link DriveGating#DRIVER_SERIALIZES} one the gate itself: a stop never waits behind more than the
 * transaction in flight, the stop epoch fences linked groups and cancels queued work, every call
 * keeps its deadline, completions never run on the I/O thread, and (since 1.6.0) {@code close()}
 * waits at most the STOP deadline, and (since 1.7.0) the provider feeds the link watchdog it armed.
 * Those cases skip for a {@link DriveGating#CALLER_SERIALIZES} provider and need
 * {@link Fixture#transactionLatency}, {@link Fixture#commandedRpm} and
 * {@link Fixture#lastRequestNanos}. Their timing bounds are wide against scheduling jitter without
 * a retry: 150 ms transactions and 250 ms slack, so a gated stop behind six queued reads is held to
 * 550 ms where an ungated one would take 1050 ms - the slack covers a starved 2-vCPU runner's
 * thread wake-ups several times over and the discrimination still holds. Only those cases carry a
 * timeout, on a thread of their own; every other case runs on the thread that opened the handle,
 * as a real driver's bench case may need.
 */
public abstract class DriveContractTest {

    /** The bus time the timing cases give each transaction. */
    protected static final Duration SLOW_TRANSACTION = Duration.ofMillis(150);

    /** Scheduling slack on top of a timing bound. */
    protected static final Duration SLACK = Duration.ofMillis(250);

    /** The four keys {@code FrequencyInverterDevice} requires of {@link Drive#getControlParameters}. */
    protected static final List<String> CONTROL_KEYS =
            List.of("start", "generalEnable", "useSecondRamp", "directionIsForward");

    /** The four keys {@code FrequencyInverterDevice} requires of {@link Drive#getMotorData}. */
    protected static final List<String> MOTOR_DATA_KEYS = List.of("speed", "current", "voltage", "torque");

    /** A drive under test and the plant behind it. */
    public interface Fixture extends AutoCloseable {

        /** A fresh handle, as the provider's {@code open()} returns it. */
        Drive open();

        /**
         * Puts the shaft at this measured speed, whatever the setpoint says - the plant's state, not
         * a request to the drive.
         */
        void measuredRpm(int rpm);

        /** From now on no request reaches the drive: every call fails as a lost link would. */
        void injectCommsFault();

        /** The longest link-loss timeout the drive can represent. */
        Duration maxLinkLossTimeout();

        /**
         * The speed reference as the drive holds it - the setpoint, not the measured speed.
         * Required of a gated provider's fixture; a fixture that cannot read it back keeps the
         * default, and {@code theStopLeavesStartAndEnableFalseAndSpeedZero} then checks the control
         * word only. A default so the 1.5.0 fixture stays additive for older drivers' fixtures.
         */
        default int commandedRpm() {
            throw new UnsupportedOperationException("this fixture cannot report the commanded speed"
                    + " (DriveContractTest.Fixture#commandedRpm)");
        }

        /**
         * From now on every transaction with the drive takes {@code latency}, on every handle; zero
         * makes transactions instant. Required of a gated provider's fixture, whose concurrency
         * cases are meaningless without it.
         */
        default void transactionLatency(Duration latency) {
            throw new UnsupportedOperationException("a gated provider's fixture must model per-transaction"
                    + " latency (DriveContractTest.Fixture#transactionLatency)");
        }

        /**
         * {@link System#nanoTime()} when the plant last answered a request, from any handle and of
         * any kind - a provider's own injected read included, since any valid telegram feeds the
         * drive's link watchdog. Required of a gated provider's fixture, whose link-loss feed case
         * is meaningless without it. A default so the 1.7.0 fixture stays additive.
         */
        default long lastRequestNanos() {
            throw new UnsupportedOperationException("a gated provider's fixture must report when the plant"
                    + " last answered a request (DriveContractTest.Fixture#lastRequestNanos)");
        }

        /**
         * The longest ramp time, in tenths of a second, the drive holds. The CFW11's 999.0 s by
         * default; a provider for another drive overrides it.
         */
        default int maxRampTimeTenths() {
            return 9990;
        }

        /** True only when a real drive and motor stand behind the handle; gates the bench cases. */
        default boolean livePlant() {
            return false;
        }

        @Override
        default void close() {
        }
    }

    protected abstract Fixture createFixture();

    protected Fixture fixture;
    protected Drive drive;

    @BeforeEach
    void openDrive() {
        fixture = createFixture();
        drive = fixture.open();
    }

    @AfterEach
    void closeDrive() throws Exception {
        try {
            drive.close();
        } catch (RuntimeException alreadyClosedOrBroken) {
            // A case may have closed it or broken its link on purpose.
        } finally {
            fixture.close();
        }
    }

    // ---- control word -----------------------------------------------------

    @Test
    void controlParametersCarryTheFourKeysTheDeckReads() {
        Map<String, Boolean> control = drive.getControlParameters();

        for (String key : CONTROL_KEYS) {
            assertThat(control).as("control parameter '%s'", key).containsKey(key);
            assertThat(control.get(key)).as("control parameter '%s'", key).isNotNull();
        }
    }

    @Test
    void motorDataCarriesTheFourKeysTheDeckReads() {
        Map<String, Integer> motorData = drive.getMotorData();

        for (String key : MOTOR_DATA_KEYS) {
            assertThat(motorData).as("motor data '%s'", key).containsKey(key);
            assertThat(motorData.get(key)).as("motor data '%s'", key).isNotNull();
        }
    }

    /**
     * Each of the four bits in turn is the only one written, from both polarities of the other
     * three; the other three must read back as they were.
     */
    @Test
    void aNullInSetControlParametersLeavesThatBitUntouched() {
        for (boolean others : new boolean[]{true, false}) {
            for (int written = 0; written < CONTROL_KEYS.size(); written++) {
                Boolean b = others;
                drive.setControlParameters(b, b, b, null, b);
                assertControlBits(others, others, others, others, "after writing all four as " + others);

                Boolean[] onlyOne = {null, null, null, null};
                onlyOne[written] = !others;
                drive.setControlParameters(onlyOne[0], onlyOne[1], onlyOne[2], null, onlyOne[3]);

                boolean[] expected = {others, others, others, others};
                expected[written] = !others;
                assertControlBits(expected[0], expected[1], expected[2], expected[3],
                        "after writing only " + bitName(written) + " = " + !others);
            }
        }
    }

    /** The deck's stop: start and general enable cleared in one write, direction and ramp kept. */
    @Test
    void theOneWriteStopKeepsDirectionAndRamp() {
        drive.setControlParameters(true, true, false, null, true);

        drive.setControlParameters(false, false, null, null, null);

        assertControlBits(false, false, false, true, "after the one-write stop");
    }

    @Test
    void eachSingleBitSetterReadsBackThroughTheControlWord() {
        for (boolean value : new boolean[]{true, false}) {
            drive.setStart(value);
            drive.setGeneralEnable(value);
            drive.setDirection(value);
            drive.setUseSecondRamp(value);

            assertControlBits(value, value, value, value, "after the four single-bit setters wrote " + value);
            assertThat(drive.getDirection()).as("getDirection").isEqualTo(value);
        }
    }

    // ---- speed ------------------------------------------------------------

    @Test
    void motorSpeedIsTheMeasuredValueNeverTheSetpoint() {
        drive.setSpeedReferenceValueAsRpm(750);
        fixture.measuredRpm(123);

        assertThat(drive.getMotorSpeedValueAsRpm()).isEqualTo(123);

        drive.setSpeedReferenceValueAsRpm(0);
        assertThat(drive.getMotorSpeedValueAsRpm())
                .as("a zero setpoint must not read back as a stopped shaft")
                .isEqualTo(123);
    }

    // ---- ramp ---------------------------------------------------------------------

    @Test
    void theSecondRampTakesItsWholeRange() {
        int max = fixture.maxRampTimeTenths();

        assertThatCode(() -> {
            drive.setSecondSpeedRampTime(0, max);
            drive.setSecondSpeedRampTime(max, 0);
        }).doesNotThrowAnyException();
    }

    /** Refused, never clamped or wrapped into a different ramp. */
    @Test
    void theSecondRampRefusesATimeItCannotHold() {
        int max = fixture.maxRampTimeTenths();

        assertThatThrownBy(() -> drive.setSecondSpeedRampTime(max + 1, 10))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> drive.setSecondSpeedRampTime(10, max + 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> drive.setSecondSpeedRampTime(-1, 10))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> drive.setSecondSpeedRampTime(10, -1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ---- link-loss stop (I19) -------------------------------------------------

    @Test
    void theLinkLossStopRefusesATimeoutItCannotRepresent() {
        drive.setActionInCaseOfCommunicationError(Drive.COMMUNICATION_ERROR_DISABLE_VIA_GENERAL_ENABLE);
        Duration max = fixture.maxLinkLossTimeout();

        assertThatThrownBy(() -> drive.enableLinkLossStop(Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> drive.enableLinkLossStop(Duration.ofMillis(-1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> drive.enableLinkLossStop(max.plusMillis(1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatCode(() -> drive.enableLinkLossStop(max)).doesNotThrowAnyException();
    }

    @Test
    void theLinkLossStopRefusesAnActionThatWouldNotStopTheMotor() {
        drive.setActionInCaseOfCommunicationError(0);

        assertThatThrownBy(() -> drive.enableLinkLossStop(Duration.ofSeconds(2)))
                .isInstanceOf(IllegalStateException.class);
    }

    /**
     * Every energize re-sends the action and the timeout, a resume over a coasting shaft included,
     * and a drive may refuse a changed value while the shaft turns. An unchanged one must pass -
     * here with a timeout the drive has to round, so "unchanged" is judged after rounding.
     */
    @Test
    void theLinkLossSettingsAreIdempotentEvenWhileTheShaftTurns() {
        Duration unrounded = Duration.ofMillis(1_250);
        drive.setActionInCaseOfCommunicationError(Drive.COMMUNICATION_ERROR_DISABLE_VIA_GENERAL_ENABLE);
        drive.enableLinkLossStop(unrounded);

        fixture.measuredRpm(500);

        assertThatCode(() -> {
            drive.setActionInCaseOfCommunicationError(Drive.COMMUNICATION_ERROR_DISABLE_VIA_GENERAL_ENABLE);
            drive.enableLinkLossStop(unrounded);
            drive.setActionInCaseOfCommunicationError(Drive.COMMUNICATION_ERROR_DISABLE_VIA_GENERAL_ENABLE);
            drive.enableLinkLossStop(unrounded);
        }).doesNotThrowAnyException();
    }

    /**
     * Since 1.7.0: the deck sends a gated provider no keep-alive, so once armed and energized the
     * provider itself keeps a request reaching the drive at least once per timeout while its caller
     * is silent. The case never de-energizes: a provider may pause the feed after a stop.
     */
    @Test
    @Timeout(value = 30, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void aGatedProviderFeedsTheLinkWatchdogItArmedWhileEnergized() throws InterruptedException {
        assumeGated();
        Duration timeout = Duration.ofMillis(900);
        drive.setActionInCaseOfCommunicationError(Drive.COMMUNICATION_ERROR_DISABLE_VIA_GENERAL_ENABLE);
        drive.enableLinkLossStop(timeout);
        drive.setSpeedReferenceValueAsRpm(300);
        drive.setControlParameters(true, true, null, null, null);

        long until = System.nanoTime() + timeout.multipliedBy(5).dividedBy(2).toNanos();
        while (System.nanoTime() < until) {
            Thread.sleep(50);
            Duration silent = Duration.ofNanos(System.nanoTime() - fixture.lastRequestNanos());
            assertThat(silent).as("time since the plant last answered a request, caller silent")
                    .isLessThanOrEqualTo(timeout);
        }
    }

    // ---- control source -----------------------------------------------------

    @Test
    void theControlSourceIsAlwaysAnswered() {
        assertThat(drive.readControlSource()).isNotNull();
    }

    // ---- handle lifecycle and failures --------------------------------------

    @Test
    void aCallAfterCloseThrows() {
        drive.close();

        assertThatThrownBy(drive::getMotorSpeedValueAsRpm).as("a read after close").isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> drive.setControlParameters(false, false, null, null, null))
                .as("a write after close")
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    void aLostLinkSurfacesAsDriveCommunicationException() {
        fixture.injectCommsFault();

        assertCommsFailure("a read", Drive::getMotorSpeedValueAsRpm);
        assertCommsFailure("the control word read", Drive::getControlParameters);
        assertCommsFailure("the motor data read", Drive::getMotorData);
        assertCommsFailure("the control source read", Drive::readControlSource);
        assertCommsFailure("a setpoint write", d -> d.setSpeedReferenceValueAsRpm(100));
        // The stop path may retry; it must still end in the typed failure, never a vendor type.
        assertCommsFailure("the one-write stop", d -> d.setControlParameters(false, false, null, null, null));
        assertCommsFailure("a general-enable stop", d -> d.setGeneralEnable(false));
        assertCommsFailure("stop()", Drive::stop);
    }

    // ---- stop (1.5.0) ------------------------------------------------------------

    @Test
    void theStopLeavesStartAndEnableFalseAndSpeedZero() {
        drive.setSpeedReferenceValueAsRpm(300);
        drive.setControlParameters(true, true, false, null, true);

        drive.stop();

        assertControlBits(false, false, false, true, "after stop()");
        commandedRpmIfReported().ifPresent(rpm ->
                assertThat(rpm).as("speed reference after stop()").isZero());
    }

    @Test
    void anUngatedDriveRefusesWhatNeedsTheGate() {
        assumeThat(drive.gating()).isEqualTo(DriveGating.CALLER_SERIALIZES);

        assertThatThrownBy(drive::stopEpoch).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> drive.runLinked(0, d -> {
        })).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(drive::async).isInstanceOf(UnsupportedOperationException.class);
    }

    // ---- the gate (1.5.0, DRIVER_SERIALIZES only) -------------------------------

    @Test
    @Timeout(value = 30, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void stopBumpsTheEpochMonotonically() {
        assumeGated();
        long before = drive.stopEpoch();

        drive.stop();
        assertThat(drive.stopEpoch()).as("after stop()").isEqualTo(before + 1);
        drive.setControlParameters(false, false, null, null, null);
        drive.setGeneralEnable(false);
        drive.setSpeedReferenceValueAsRpm(0);
        assertThat(drive.stopEpoch()).as("after three more STOP-lane writes").isEqualTo(before + 4);

        drive.getMotorData();
        drive.setSpeedReferenceValueAsRpm(300);
        drive.setControlParameters(true, true, null, null, null);
        assertThat(drive.stopEpoch()).as("after a read, a setpoint and an energizing write")
                .isEqualTo(before + 4);
    }

    @Test
    @Timeout(value = 30, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void aGroupSubmittedWithAStaleEpochRunsNothing() {
        assumeGated();
        long epoch = drive.stopEpoch();
        drive.stop();
        var ran = new AtomicBoolean();

        assertThatThrownBy(() -> drive.runLinked(epoch, d -> {
            ran.set(true);
            d.setSpeedReferenceValueAsRpm(300);
            d.setControlParameters(true, true, null, null, null);
        })).isInstanceOf(StaleStopEpochException.class);

        assertThat(ran).as("the group's body ran").isFalse();
        assertThat(fixture.commandedRpm()).isZero();
        Map<String, Boolean> control = drive.getControlParameters();
        assertThat(control.get("start")).as("start").isFalse();
        assertThat(control.get("generalEnable")).as("generalEnable").isFalse();
    }

    @Test
    @Timeout(value = 30, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void aGroupWithACurrentEpochRunsItsCalls() {
        assumeGated();

        drive.runLinked(drive.stopEpoch(), d -> {
            d.setSpeedReferenceValueAsRpm(300);
            d.setControlParameters(true, true, false, null, false);
        });

        assertThat(fixture.commandedRpm()).isEqualTo(300);
        assertControlBits(true, true, false, false, "after the group");
    }

    @Test
    @Timeout(value = 30, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void aStopDuringAGroupCancelsItsRemainder() throws InterruptedException {
        assumeGated();
        var inGroup = new CountDownLatch(1);
        var stopped = new CountDownLatch(1);
        Thread stopper = Thread.ofPlatform().start(() -> {
            await(inGroup);
            drive.stop();
            stopped.countDown();
        });

        assertThatThrownBy(() -> drive.runLinked(drive.stopEpoch(), d -> {
            d.setSpeedReferenceValueAsRpm(300);
            inGroup.countDown();
            await(stopped);
            d.setControlParameters(true, true, null, null, null);
        })).isInstanceOf(StaleStopEpochException.class);
        stopper.join();

        assertThat(fixture.commandedRpm()).as("the stop's zero speed").isZero();
        Map<String, Boolean> control = drive.getControlParameters();
        assertThat(control.get("start")).as("start").isFalse();
        assertThat(control.get("generalEnable")).as("generalEnable").isFalse();
    }

    /**
     * A stop from the body cuts the group, but the body keeps running: the handle refuses it every
     * call but a stop until the body returns, so no energizing write follows the stop.
     */
    @Test
    @Timeout(value = 30, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void aBodyCutByAStopCanOnlyStopThroughTheHandle() {
        assumeGated();

        drive.runLinked(drive.stopEpoch(), d -> {
            d.setSpeedReferenceValueAsRpm(300);
            drive.stop();
            assertThatThrownBy(() -> drive.setControlParameters(true, true, null, null, null))
                    .as("an energizing write on the handle").isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> drive.async().setStart(true))
                    .as("an energizing write on the async view").isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> drive.runLinked(drive.stopEpoch(), inner -> inner.setStart(true)))
                    .as("a nested group").isInstanceOf(IllegalStateException.class);
            drive.setGeneralEnable(false);
        });

        assertThat(fixture.commandedRpm()).as("the stop's zero speed").isZero();
        Map<String, Boolean> control = drive.getControlParameters();
        assertThat(control.get("start")).as("start").isFalse();
        assertThat(control.get("generalEnable")).as("generalEnable").isFalse();
    }

    /**
     * A STOP-lane call on the {@code Drive} handed to the body goes out like any other stop and
     * cuts the group: the body's next call is refused as stale, so no energizing write follows it.
     */
    @Test
    @Timeout(value = 30, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void aStopThroughTheGroupsOwnDriveCutsTheGroup() {
        assumeGated();

        assertThatThrownBy(() -> drive.runLinked(drive.stopEpoch(), d -> {
            d.setSpeedReferenceValueAsRpm(0);
            d.setControlParameters(true, true, null, null, null);
        })).isInstanceOf(StaleStopEpochException.class);

        assertThat(fixture.commandedRpm()).as("the stop's zero speed").isZero();
        Map<String, Boolean> control = drive.getControlParameters();
        assertThat(control.get("start")).as("start").isFalse();
        assertThat(control.get("generalEnable")).as("generalEnable").isFalse();
    }

    /**
     * Six reads queued behind a slow one would hold an ungated stop for seven transactions; the
     * gate lets it wait for the one in flight only. The time bound carries scheduling slack wider
     * than a transaction, so the order is checked on its own: no queued read may have completed
     * when the stop returns, which holds for any provider whose STATUS deadline outlasts two
     * transactions.
     */
    @Test
    @Timeout(value = 30, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void aStopDuringASlowCallCompletesWithinOneTransactionPlusItsOwn() throws InterruptedException {
        assumeGated();
        fixture.transactionLatency(SLOW_TRANSACTION);
        drive.async().getMotorData();
        Thread.sleep(SLOW_TRANSACTION.toMillis() / 3);
        List<CompletableFuture<Map<String, Integer>>> queued = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            queued.add(drive.async().getMotorData());
        }

        long started = System.nanoTime();
        drive.stop();
        Duration took = Duration.ofNanos(System.nanoTime() - started);

        assertThat(queued).as("reads queued before the stop, when it returns")
                .noneMatch(CompletableFuture::isDone);
        assertThat(took).as("stop behind one transaction in flight and six queued")
                .isLessThanOrEqualTo(SLOW_TRANSACTION.multipliedBy(2).plus(SLACK));
    }

    @Test
    @Timeout(value = 30, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void queuedStartNeverRunsAfterAStop() throws InterruptedException {
        assumeGated();
        fixture.transactionLatency(SLOW_TRANSACTION);
        drive.async().getMotorData();
        Thread.sleep(SLOW_TRANSACTION.toMillis() / 3);

        var speed = drive.async().setSpeedReferenceValueAsRpm(500);
        var start = drive.async().setControlParameters(true, true, null, null, null);
        drive.stop();

        assertStale("the queued setpoint", speed);
        assertStale("the queued start", start);
        fixture.transactionLatency(Duration.ZERO);
        assertThat(fixture.commandedRpm()).isZero();
        Map<String, Boolean> control = drive.getControlParameters();
        assertThat(control.get("start")).as("start").isFalse();
        assertThat(control.get("generalEnable")).as("generalEnable").isFalse();
    }

    /** A stop whose own transaction outlasts the contract's STOP deadline. */
    @Test
    @Timeout(value = 30, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void anOutlastedDeadlineSurfacesAsDriveDeadlineException() {
        assumeGated();
        fixture.transactionLatency(Duration.ofMillis(2_500));

        assertThatThrownBy(drive::stop)
                .isInstanceOf(DriveDeadlineException.class)
                .isInstanceOf(DriveCommunicationException.class);
    }

    /**
     * close() returns only with nothing in flight and the port closed, so a fresh handle opened
     * after it never overlaps this session; a stop queued at close still reaches the drive.
     */
    @Test
    @Timeout(value = 30, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void closeWaitsForTheInFlightTransactionDrainsStopsAndFailsTheRest() throws InterruptedException {
        assumeGated();
        drive.setSpeedReferenceValueAsRpm(300);
        drive.setControlParameters(true, true, null, null, null);
        fixture.transactionLatency(SLOW_TRANSACTION);
        var inFlight = drive.async().getMotorData();
        Thread.sleep(SLOW_TRANSACTION.toMillis() / 3);
        var queuedRead = drive.async().getMotorSpeedValueAsRpm();
        var queuedStop = drive.async().stop();

        long started = System.nanoTime();
        drive.close();
        Duration took = Duration.ofNanos(System.nanoTime() - started);

        assertThat(took).as("close waits for the transaction in flight and the queued stop only")
                .isLessThanOrEqualTo(SLOW_TRANSACTION.multipliedBy(2).plus(SLACK));
        assertThat(inFlight.join()).as("the transaction in flight").containsKey("speed");
        assertThatCode(queuedStop::join).as("the queued stop").doesNotThrowAnyException();
        assertThat(fixture.commandedRpm()).as("the queued stop reached the plant before close returned")
                .isZero();
        assertThatThrownBy(queuedRead::join).as("the queued read")
                .isInstanceOf(CompletionException.class)
                .hasCauseInstanceOf(IllegalStateException.class);
        assertThatThrownBy(drive::getMotorData).as("a call after close")
                .isInstanceOf(IllegalStateException.class);
        assertThatCode(drive::close).as("a second close").doesNotThrowAnyException();
    }

    /**
     * Since 1.6.0: close() waits at most the STOP deadline. A transaction in flight past it leaves
     * the port held, so close() says so rather than return; the stop queued before close() still
     * reaches the plant once the transfer ends, and a later close() returns normally - the point by
     * which the contract has the port closed. Whether a provider closes the port sooner, by itself,
     * is not asserted here: a provider that closes it on the closing thread does so in that later
     * call.
     */
    @Test
    @Timeout(value = 30, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void closeDuringATransferLongerThanTheStopDeadlineThrowsPendingAndALaterCloseReturns()
            throws InterruptedException {
        assumeGated();
        drive.setSpeedReferenceValueAsRpm(300);
        drive.setControlParameters(true, true, null, null, null);
        Duration stopDeadline = Duration.ofMillis(DriveLane.STOP_DEADLINE_MILLIS);
        fixture.transactionLatency(stopDeadline.plusMillis(500));
        var inFlight = drive.async().getMotorData();
        Thread.sleep(SLOW_TRANSACTION.toMillis() / 3);
        var queuedStop = drive.async().stop();

        long started = System.nanoTime();
        assertThatThrownBy(drive::close).as("a close past the STOP deadline")
                .isInstanceOf(DriveClosePendingException.class);
        Duration took = Duration.ofNanos(System.nanoTime() - started);
        // The stop that goes out once the transfer ends is instant, so the handle closes soon after.
        fixture.transactionLatency(Duration.ZERO);

        assertThat(took).as("close is bounded by the STOP deadline").isLessThanOrEqualTo(stopDeadline.plus(SLACK));
        assertThatThrownBy(drive::getMotorData).as("a call after the pending close")
                .isInstanceOf(IllegalStateException.class);
        assertThatCode(drive::close).as("a later close, once the transfer ended").doesNotThrowAnyException();
        assertThat(inFlight).as("the transaction in flight, answered or past its deadline").isDone();
        assertThatThrownBy(queuedStop::join).as("the stop queued before close outlived its deadline")
                .hasCauseInstanceOf(DriveDeadlineException.class);
        assertThat(fixture.commandedRpm()).as("but still reached the plant before the port closed").isZero();
    }

    /** A dependent stage that calls the drive synchronously would be refused on the I/O thread. */
    @Test
    @Timeout(value = 30, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void theAsyncViewCompletesOffTheProviderThread() throws Exception {
        assumeGated();
        fixture.transactionLatency(SLOW_TRANSACTION);

        CompletableFuture<Integer> chained = drive.async().getDirection()
                .thenApply(direction -> drive.getMotorSpeedValueAsRpm());

        assertThat(chained.get(5, TimeUnit.SECONDS)).isNotNull();
    }

    // ---- bench ----------------------------------------------------------------

    /** The one-write stop, observed on the shaft rather than the control word. */
    @Test
    @Tag("bench")
    void theOneWriteStopBringsAnEnergizedShaftToRest() throws InterruptedException {
        assumeThat(fixture.livePlant()).as("a real drive and motor").isTrue();
        drive.setActionInCaseOfCommunicationError(Drive.COMMUNICATION_ERROR_DISABLE_VIA_GENERAL_ENABLE);
        drive.enableLinkLossStop(Duration.ofSeconds(2));
        drive.setSpeedReferenceValueAsRpm(100);
        drive.setControlParameters(true, true, true, null, null);
        Thread.sleep(1_000);

        drive.setControlParameters(false, false, null, null, null);

        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (drive.getMotorSpeedValueAsRpm() != 0 && System.nanoTime() < deadline) {
            Thread.sleep(100);
        }
        assertThat(drive.getMotorSpeedValueAsRpm()).isZero();
    }

    // ---- helpers ------------------------------------------------------------

    private OptionalInt commandedRpmIfReported() {
        try {
            return OptionalInt.of(fixture.commandedRpm());
        } catch (UnsupportedOperationException e) {
            return OptionalInt.empty();
        }
    }

    private void assumeGated() {
        assumeThat(drive.gating()).as("a provider that gates its own calls")
                .isEqualTo(DriveGating.DRIVER_SERIALIZES);
    }

    private static void assertStale(String what, CompletableFuture<?> future) {
        assertThatThrownBy(future::join).as(what)
                .isInstanceOf(CompletionException.class)
                .hasCauseInstanceOf(StaleStopEpochException.class);
    }

    private static void await(CountDownLatch latch) {
        try {
            assertThat(latch.await(5, TimeUnit.SECONDS)).as("latch released").isTrue();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private void assertCommsFailure(String what, Consumer<Drive> call) {
        assertThatThrownBy(() -> call.accept(drive))
                .as(what)
                .isInstanceOf(DriveCommunicationException.class);
    }

    private void assertControlBits(boolean start, boolean generalEnable, boolean directionIsForward,
                                   boolean useSecondRamp, String when) {
        Map<String, Boolean> control = drive.getControlParameters();
        assertThat(control.get("start")).as("start %s", when).isEqualTo(start);
        assertThat(control.get("generalEnable")).as("generalEnable %s", when).isEqualTo(generalEnable);
        assertThat(control.get("directionIsForward")).as("directionIsForward %s", when).isEqualTo(directionIsForward);
        assertThat(control.get("useSecondRamp")).as("useSecondRamp %s", when).isEqualTo(useSecondRamp);
    }

    /** Positions as {@link #aNullInSetControlParametersLeavesThatBitUntouched} writes them. */
    private static String bitName(int position) {
        return switch (position) {
            case 0 -> "start";
            case 1 -> "generalEnable";
            case 2 -> "directionIsForward";
            default -> "useSecondRamp";
        };
    }
}
