package ch.rupfizupfi.deck.device.api;

/**
 * A gated {@link Drive#close()} that gave up waiting: the transaction in flight has not ended
 * within the contract's {@link DriveLane#STOP_DEADLINE_MILLIS}, so the port is still held. Since
 * contract 1.6.0.
 * <p>
 * The handle is closed to callers all the same. Its I/O thread finishes the transfer and sends
 * every STOP-lane call queued before the close. The port is closed no later than the first later
 * {@code close()} that returns normally, so calling it again is how a caller waits for it; a
 * provider that hands its port close to the I/O thread also closes it by itself once the transfer
 * ends, one that closes it on the closing thread only in that later {@code close()}. Until then a
 * fresh handle on the same drive would be a second session beside the transfer.
 * <p>
 * Not a {@link DriveCommunicationException}: nothing failed on the wire, and a caller that treats
 * a comms failure as "the handle is gone" must not read a pending close that way.
 */
public class DriveClosePendingException extends RuntimeException {

    public DriveClosePendingException(String message) {
        super(message);
    }
}
