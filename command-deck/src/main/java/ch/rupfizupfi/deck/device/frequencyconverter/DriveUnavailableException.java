package ch.rupfizupfi.deck.device.frequencyconverter;

/**
 * Thrown when drive access is requested while no CFW11 handle is open.
 */
public class DriveUnavailableException extends RuntimeException {
    public DriveUnavailableException(String message) {
        super(message);
    }
}
