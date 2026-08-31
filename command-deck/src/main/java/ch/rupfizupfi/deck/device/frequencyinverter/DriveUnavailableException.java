package ch.rupfizupfi.deck.device.frequencyinverter;

/**
 * Thrown when drive access is requested while no frequency inverter drive handle is open.
 */
public class DriveUnavailableException extends RuntimeException {
    public DriveUnavailableException(String message) {
        super(message);
    }
}
