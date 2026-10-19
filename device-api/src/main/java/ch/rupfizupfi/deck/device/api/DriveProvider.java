package ch.rupfizupfi.deck.device.api;

/**
 * Opens a drive handle. A factory because safe-stop tier 2
 * ({@code MotorSafetyController#stopWithFreshHandle}) opens a fresh handle mid-stop.
 */
public interface DriveProvider {

    /** Opens the hardware immediately; the caller owns the returned handle and must {@code close()} it. */
    Drive open();
}
