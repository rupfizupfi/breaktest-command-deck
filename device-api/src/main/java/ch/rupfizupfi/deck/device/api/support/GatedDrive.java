package ch.rupfizupfi.deck.device.api.support;

import ch.rupfizupfi.deck.device.api.ControlSource;
import ch.rupfizupfi.deck.device.api.Drive;
import ch.rupfizupfi.deck.device.api.DriveAsync;
import ch.rupfizupfi.deck.device.api.DriveGating;
import ch.rupfizupfi.deck.device.api.DriveLane;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * A {@link DriveGating#DRIVER_SERIALIZES} {@link Drive} over a provider's {@link DriveOperations}:
 * every call becomes one transaction on a {@link TransactionScheduler}, in the lane its operation
 * implies ({@link DriveLane}'s table). A synchronous method submits the same transaction as its
 * {@linkplain #async() async} twin but waits on the transaction itself, not on the async view's
 * future, for at most the lane's deadline plus a short slack ({@link TransactionScheduler#call}): a
 * slow completion thread cannot turn a stop that went out into a deadline failure.
 * <p>
 * A provider either extends this class or returns one from its {@code open()}; either way its own
 * code is only the operations, which run on the I/O thread alone.
 */
public class GatedDrive implements Drive {

    private final DriveOperations operations;
    private final TransactionScheduler scheduler;
    /** The linked group this view submits to; null on the handle itself. */
    private final TransactionScheduler.LinkedGroup group;
    private final DriveAsync async;

    /**
     * Starts the handle's I/O thread.
     *
     * @param ioThreadName the I/O thread's name; one per handle, so it should be distinct
     */
    public GatedDrive(DriveOperations operations, String ioThreadName, LaneDeadlines deadlines) {
        this(operations, new TransactionScheduler(ioThreadName, deadlines,
                Objects.requireNonNull(operations, "operations")::close), null);
    }

    private GatedDrive(DriveOperations operations, TransactionScheduler scheduler,
                       TransactionScheduler.LinkedGroup group) {
        this.operations = Objects.requireNonNull(operations);
        this.scheduler = scheduler;
        this.group = group;
        this.async = group == null ? new Async() : null;
    }

    /**
     * The lane of a control-word write: STOP only for the pure one-write stop - neither start nor
     * general enable set, at least one of them cleared, and direction, LOC/REM and ramp select left
     * untouched (null). Anything else is CONTROL: a write that also changes another bit must not
     * escape stale cancellation or the group-owner refusal by riding in the STOP lane.
     */
    public static DriveLane laneOfControlParameters(Boolean start, Boolean generalEnable,
                                                    Boolean directionIsForward, Boolean localRemote,
                                                    Boolean useSecondRamp) {
        boolean energizes = Boolean.TRUE.equals(start) || Boolean.TRUE.equals(generalEnable);
        boolean stops = Boolean.FALSE.equals(start) || Boolean.FALSE.equals(generalEnable);
        boolean touchesOtherBits = directionIsForward != null || localRemote != null || useSecondRamp != null;
        return stops && !energizes && !touchesOtherBits ? DriveLane.STOP : DriveLane.CONTROL;
    }

    /** Zero speed is part of every stop; any other speed is a setpoint. */
    public static DriveLane laneOfSpeedReference(int rpm) {
        return rpm == 0 ? DriveLane.STOP : DriveLane.SETPOINT;
    }

    private static DriveLane laneOfBit(boolean value) {
        return value ? DriveLane.CONTROL : DriveLane.STOP;
    }

    /** The I/O thread's name, which no completion and no caller ever runs on. */
    public String ioThreadName() {
        return scheduler.name();
    }

    // ---- gating ---------------------------------------------------------------------------------

    @Override
    public DriveGating gating() {
        return DriveGating.DRIVER_SERIALIZES;
    }

    @Override
    public long stopEpoch() {
        return scheduler.stopEpoch();
    }

    @Override
    public void runLinked(long expectedEpoch, Consumer<Drive> body) {
        Objects.requireNonNull(body, "body");
        if (group != null) {
            throw new IllegalStateException("linked groups do not nest");
        }
        try (var linked = scheduler.openGroup(expectedEpoch)) {
            body.accept(new GatedDrive(operations, scheduler, linked));
        }
    }

    @Override
    public DriveAsync async() {
        if (group != null) {
            throw new IllegalStateException("a linked group's calls are synchronous");
        }
        return async;
    }

    // ---- STOP -----------------------------------------------------------------------------------

    /** One STOP-lane transaction, never the default's two calls. */
    @Override
    public void stop() {
        call(DriveLane.STOP, "stop", run(operations::stop));
    }

    // ---- control word ---------------------------------------------------------------------------

    @Override
    public void setControlParameters(Boolean start, Boolean generalEnable, Boolean directionIsForward,
                                     Boolean localRemote, Boolean useSecondRamp) {
        call(laneOfControlParameters(start, generalEnable, directionIsForward, localRemote, useSecondRamp),
                "setControlParameters",
                run(() -> operations.setControlParameters(start, generalEnable, directionIsForward,
                        localRemote, useSecondRamp)));
    }

    @Override
    public Map<String, Boolean> getControlParameters() {
        return call(DriveLane.STATUS, "getControlParameters", operations::getControlParameters);
    }

    @Override
    public ControlSource readControlSource() {
        return call(DriveLane.STATUS, "readControlSource", operations::readControlSource);
    }

    @Override
    public void setStart(boolean start) {
        call(laneOfBit(start), "setStart", run(() -> operations.setStart(start)));
    }

    @Override
    public void setGeneralEnable(boolean generalEnable) {
        call(laneOfBit(generalEnable), "setGeneralEnable", run(() -> operations.setGeneralEnable(generalEnable)));
    }

    @Override
    public void setDirection(boolean directionIsForward) {
        call(DriveLane.CONTROL, "setDirection", run(() -> operations.setDirection(directionIsForward)));
    }

    @Override
    public boolean getDirection() {
        return call(DriveLane.STATUS, "getDirection", operations::getDirection);
    }

    @Override
    public void setUseSecondRamp(boolean useSecondRamp) {
        call(DriveLane.CONTROL, "setUseSecondRamp", run(() -> operations.setUseSecondRamp(useSecondRamp)));
    }

    // ---- setpoint -------------------------------------------------------------------------------

    @Override
    public void setSecondSpeedRampTime(int accelerationRampTime, int decelerationRampTime) {
        call(DriveLane.SETPOINT, "setSecondSpeedRampTime",
                run(() -> operations.setSecondSpeedRampTime(accelerationRampTime, decelerationRampTime)));
    }

    @Override
    public void setSpeedReferenceValueAsRpm(int rpm) {
        call(laneOfSpeedReference(rpm), "setSpeedReferenceValueAsRpm",
                run(() -> operations.setSpeedReferenceValueAsRpm(rpm)));
    }

    // ---- status ---------------------------------------------------------------------------------

    @Override
    public int getMotorSpeedValueAsRpm() {
        return call(DriveLane.STATUS, "getMotorSpeedValueAsRpm", operations::getMotorSpeedValueAsRpm);
    }

    @Override
    public Map<String, Integer> getMotorData() {
        return call(DriveLane.STATUS, "getMotorData", operations::getMotorData);
    }

    // ---- config ---------------------------------------------------------------------------------

    @Override
    public void setActionInCaseOfCommunicationError(int action) {
        call(DriveLane.CONFIG, "setActionInCaseOfCommunicationError",
                run(() -> operations.setActionInCaseOfCommunicationError(action)));
    }

    @Override
    public void enableLinkLossStop(Duration timeout) {
        call(DriveLane.CONFIG, "enableLinkLossStop", run(() -> operations.enableLinkLossStop(timeout)));
    }

    // ---- lifecycle ------------------------------------------------------------------------------

    /**
     * Rejects new calls, fails the queued ones except stops, and waits at most the STOP deadline
     * for the I/O thread to finish the transaction in flight, send the queued stops and close the
     * port ({@link DriveOperations#close()}, its last act). Returns once the port is closed, so a
     * fresh handle opened afterwards never overlaps this one's session; past the bound it throws
     * and the I/O thread closes the port by itself once the transfer ends
     * ({@link TransactionScheduler#close()}). Idempotent: a concurrent or later call waits the same
     * bound and returns once the port is closed.
     *
     * @throws ch.rupfizupfi.deck.device.api.DriveClosePendingException if the transfer in flight
     *                                                                    outlasts the bound
     * @throws IllegalStateException inside a linked group, or on the I/O thread
     */
    @Override
    public void close() {
        if (group != null || scheduler.isInsideOwnGroup()) {
            throw new IllegalStateException("close() called from inside a linked group; close the"
                    + " handle once the group's body has returned");
        }
        scheduler.close();
    }

    private <T> T call(DriveLane lane, String operation, Callable<T> work) {
        return group == null
                ? scheduler.call(lane, operation, work)
                : scheduler.callLinked(group, lane, operation, work);
    }

    private <T> CompletableFuture<T> submit(DriveLane lane, String operation, Callable<T> work) {
        return scheduler.submit(lane, operation, work);
    }

    private static Callable<Void> run(Runnable work) {
        return () -> {
            work.run();
            return null;
        };
    }

    /** The same operations in the same lanes, without the join. */
    private final class Async implements DriveAsync {

        @Override
        public CompletableFuture<Void> stop() {
            return submit(DriveLane.STOP, "stop", run(operations::stop));
        }

        @Override
        public CompletableFuture<Void> setControlParameters(Boolean start, Boolean generalEnable,
                                                            Boolean directionIsForward, Boolean localRemote,
                                                            Boolean useSecondRamp) {
            return submit(laneOfControlParameters(start, generalEnable, directionIsForward, localRemote,
                    useSecondRamp), "setControlParameters",
                    run(() -> operations.setControlParameters(start, generalEnable, directionIsForward,
                            localRemote, useSecondRamp)));
        }

        @Override
        public CompletableFuture<Map<String, Boolean>> getControlParameters() {
            return submit(DriveLane.STATUS, "getControlParameters", operations::getControlParameters);
        }

        @Override
        public CompletableFuture<ControlSource> readControlSource() {
            return submit(DriveLane.STATUS, "readControlSource", operations::readControlSource);
        }

        @Override
        public CompletableFuture<Void> setStart(boolean start) {
            return submit(laneOfBit(start), "setStart", run(() -> operations.setStart(start)));
        }

        @Override
        public CompletableFuture<Void> setGeneralEnable(boolean generalEnable) {
            return submit(laneOfBit(generalEnable), "setGeneralEnable",
                    run(() -> operations.setGeneralEnable(generalEnable)));
        }

        @Override
        public CompletableFuture<Void> setDirection(boolean directionIsForward) {
            return submit(DriveLane.CONTROL, "setDirection", run(() -> operations.setDirection(directionIsForward)));
        }

        @Override
        public CompletableFuture<Boolean> getDirection() {
            return submit(DriveLane.STATUS, "getDirection", operations::getDirection);
        }

        @Override
        public CompletableFuture<Void> setUseSecondRamp(boolean useSecondRamp) {
            return submit(DriveLane.CONTROL, "setUseSecondRamp", run(() -> operations.setUseSecondRamp(useSecondRamp)));
        }

        @Override
        public CompletableFuture<Void> setSecondSpeedRampTime(int accelerationRampTime, int decelerationRampTime) {
            return submit(DriveLane.SETPOINT, "setSecondSpeedRampTime",
                    run(() -> operations.setSecondSpeedRampTime(accelerationRampTime, decelerationRampTime)));
        }

        @Override
        public CompletableFuture<Void> setSpeedReferenceValueAsRpm(int rpm) {
            return submit(laneOfSpeedReference(rpm), "setSpeedReferenceValueAsRpm",
                    run(() -> operations.setSpeedReferenceValueAsRpm(rpm)));
        }

        @Override
        public CompletableFuture<Integer> getMotorSpeedValueAsRpm() {
            return submit(DriveLane.STATUS, "getMotorSpeedValueAsRpm", operations::getMotorSpeedValueAsRpm);
        }

        @Override
        public CompletableFuture<Map<String, Integer>> getMotorData() {
            return submit(DriveLane.STATUS, "getMotorData", operations::getMotorData);
        }

        @Override
        public CompletableFuture<Void> setActionInCaseOfCommunicationError(int action) {
            return submit(DriveLane.CONFIG, "setActionInCaseOfCommunicationError",
                    run(() -> operations.setActionInCaseOfCommunicationError(action)));
        }

        @Override
        public CompletableFuture<Void> enableLinkLossStop(Duration timeout) {
            return submit(DriveLane.CONFIG, "enableLinkLossStop", run(() -> operations.enableLinkLossStop(timeout)));
        }
    }
}
