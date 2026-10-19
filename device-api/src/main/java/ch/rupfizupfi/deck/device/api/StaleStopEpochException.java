package ch.rupfizupfi.deck.device.api;

/**
 * A request a stop overtook: a {@linkplain Drive#runLinked linked group} submitted with a
 * {@linkplain Drive#stopEpoch() stop epoch} a stop has since advanced, a group a stop cut short
 * mid-way, or a CONTROL or SETPOINT call still queued when a stop arrived. The call that throws it
 * never reached the drive, and neither did anything after it in its group; a group's earlier calls
 * did, before the stop. Since contract 1.5.0.
 * <p>
 * Not a {@link DriveCommunicationException}: the link is fine and a stop overtook the request. That
 * stop may still be queued behind the transaction in flight, or have been refused because the handle
 * closed first, so this exception is no proof that it reached the drive. A caller treats it as "the
 * stop won the race", never as a reason to retry; whether the stop itself went out is its own
 * caller's answer.
 */
public class StaleStopEpochException extends RuntimeException {

    private final long expectedEpoch;
    private final long currentEpoch;

    public StaleStopEpochException(String message, long expectedEpoch, long currentEpoch) {
        super(message + " (expected stop epoch " + expectedEpoch + ", now " + currentEpoch + ")");
        this.expectedEpoch = expectedEpoch;
        this.currentEpoch = currentEpoch;
    }

    public long expectedEpoch() {
        return expectedEpoch;
    }

    public long currentEpoch() {
        return currentEpoch;
    }
}
