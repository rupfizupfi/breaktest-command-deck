package ch.rupfizupfi.deck.device.api;

/**
 * Why a load cell stream stopped delivering, as the driver reports it.
 * <p>
 * Raw driver facts only; operator-facing wording lives in {@code LoadCellDevice#getStreamFailure}.
 *
 * @param driverCode the driver's own error code, or null when the failure carries none
 * @param failureType simple class name of the underlying throwable; never null
 * @param message    the driver's message, verbatim; may be null
 */
public record StreamFailure(String driverCode, String failureType, String message) {
}
