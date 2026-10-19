package ch.rupfizupfi.deck.device.api;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * The asynchronous view of a gated {@link Drive}, from {@link Drive#async()}: one method per
 * semantic operation, same lane, same arguments, same refusals. Since contract 1.5.0.
 * <p>
 * A returned future completes with what the synchronous method returns, or fails with what it
 * throws ({@link DriveCommunicationException}, {@link DriveDeadlineException},
 * {@link StaleStopEpochException}, {@link IllegalArgumentException}, {@link IllegalStateException}),
 * never with a vendor type. Once its lane's deadline from submission has passed without a reply,
 * a future fails with {@link DriveDeadlineException}.
 * <p>
 * Completions never run on the provider's I/O thread: a dependent stage cannot stall the bus. The
 * synchronous method submits the same request but does not wait on this future: it waits on the
 * transaction itself, for the lane's deadline plus a short slack, so a slow completion thread cannot
 * turn a stop that went out into a deadline failure. A call
 * on a closed handle throws {@link IllegalStateException} at once instead of returning a future.
 * Cancelling a returned future does not withdraw the request.
 */
public interface DriveAsync {

    CompletableFuture<Void> stop();

    CompletableFuture<Void> setControlParameters(Boolean start, Boolean generalEnable,
                                                 Boolean directionIsForward, Boolean localRemote,
                                                 Boolean useSecondRamp);

    CompletableFuture<Map<String, Boolean>> getControlParameters();

    CompletableFuture<ControlSource> readControlSource();

    CompletableFuture<Void> setStart(boolean start);

    CompletableFuture<Void> setGeneralEnable(boolean generalEnable);

    CompletableFuture<Void> setDirection(boolean directionIsForward);

    CompletableFuture<Boolean> getDirection();

    CompletableFuture<Void> setUseSecondRamp(boolean useSecondRamp);

    CompletableFuture<Void> setSecondSpeedRampTime(int accelerationRampTime, int decelerationRampTime);

    CompletableFuture<Void> setSpeedReferenceValueAsRpm(int rpm);

    CompletableFuture<Integer> getMotorSpeedValueAsRpm();

    CompletableFuture<Map<String, Integer>> getMotorData();

    CompletableFuture<Void> setActionInCaseOfCommunicationError(int action);

    CompletableFuture<Void> enableLinkLossStop(Duration timeout);
}
