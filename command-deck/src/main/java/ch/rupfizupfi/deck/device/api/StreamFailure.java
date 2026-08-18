package ch.rupfizupfi.deck.device.api;

/**
 * Why a load cell stream stopped delivering, as the driver reports it.
 * <p>
 * Raw driver facts only — the operator-facing wording is policy and stays in
 * {@code LoadCellDevice#getStreamFailure}. {@link #driverCode()} is what tells the two cases apart:
 * a failure the driver's own error table explains reads differently from a reader thread that simply
 * died, and neither adapter may decide that.
 *
 * @param driverCode the driver's own error code, or null when the failure carries none
 * @param failureType simple class name of the underlying throwable; never null
 * @param message    the driver's message, verbatim; may be null
 */
public record StreamFailure(String driverCode, String failureType, String message) {
}
