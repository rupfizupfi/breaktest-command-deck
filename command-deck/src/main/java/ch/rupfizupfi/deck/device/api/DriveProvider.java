package ch.rupfizupfi.deck.device.api;

/**
 * Opens a drive handle. A factory and not a singleton because the safe-stop escalation's tier 2
 * ({@code MotorSafetyController#stopWithFreshHandle}) drops the live handle and enumerates the drive
 * again mid-stop.
 */
public interface DriveProvider {

    /** Opens the hardware immediately; the caller owns the returned handle and must {@code close()} it. */
    Drive open();
}
