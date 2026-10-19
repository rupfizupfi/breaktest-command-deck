package ch.rupfizupfi.deck.device.api;

/**
 * A gated {@link Drive} call whose deadline, counted from submission, passed before it was
 * dispatched or before its reply arrived. A comms failure, because to the caller it is one: the
 * request may never have reached the drive, or reached it with no answer in time. Since contract
 * 1.5.0.
 * <p>
 * Thrown for a dispatched transaction too, while the provider's I/O thread is still waiting on it:
 * nothing aborts a transfer in flight. The handle stays usable; later calls queue behind that
 * transaction and fail the same way until it ends. A STOP-lane request is the exception to "never
 * sent": still queued at its deadline, it goes out once the transaction ahead of it ends, and a
 * {@code close()} sends it before the port closes.
 * <p>
 * The deck's safe-stop tier 1 maps it to the "wedged" outcome ({@code TierOutcome#wedged}): the
 * handle did not take the stop in time, and since a fresh handle would put a second session beside
 * the transaction still in flight, tier 2 is not tried and tier 3 tells the operator.
 */
public class DriveDeadlineException extends DriveCommunicationException {

    private final DriveLane lane;

    /** @param lane the lane whose deadline passed */
    public DriveDeadlineException(DriveLane lane, String message) {
        super(message);
        this.lane = lane;
    }

    /** The lane whose deadline passed. */
    public DriveLane lane() {
        return lane;
    }
}
