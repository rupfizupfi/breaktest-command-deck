package ch.rupfizupfi.deck.device.api;

/**
 * A request to the drive whose exchange failed on the wire: a timeout, a lost or garbled frame, a
 * handle whose transport stopped answering, or a protocol-level rejection such as a Modbus exception
 * response. How a provider names a comms failure as it crosses {@link Drive}, which declares no
 * checked exceptions, so the deck can name it without naming a vendor type. Since contract 1.4.0.
 * <p>
 * Not for what the provider decides itself - an argument it refuses
 * ({@link IllegalArgumentException}), a setting that did not read back or would not stop the motor
 * ({@link IllegalStateException}) - and not for a handle the caller already closed. The cause is
 * the provider's own failure, verbatim.
 * <p>
 * Open for subclassing so a provider can keep its own type (the CFW11 driver's
 * {@code Cfw11CommunicationException}). A plugin built before 1.4.0 throws whatever it threw
 * before, so a caller that must survive every comms failure still catches more than this.
 */
public class DriveCommunicationException extends RuntimeException {

    public DriveCommunicationException(String message, Throwable cause) {
        super(message, cause);
    }

    public DriveCommunicationException(String message) {
        super(message);
    }
}
