package ch.rupfizupfi.deck.testrunner;

/**
 * Outcome of a {@link MotorSafetyController#safeStop(String)} attempt.
 *
 * @param tier              the highest tier that was reached
 * @param verified          true only if the motor was read back as standing still
 * @param driveResponsive   true if the drive took the stop commands and answered at least one speed
 *                          read, i.e. the USB handle demonstrably works
 * @param motorWasEnergized true if this run had genuinely enabled the drive output stage before the
 *                          stop, i.e. there was a live motor for the stop to catch
 * @param motorSpeedRpm     last measured motor speed, or null if none could be read
 * @param detail            human readable trace for the test log
 */
public record SafeStopResult(Tier tier, boolean verified, boolean driveResponsive,
                             boolean motorWasEnergized, Integer motorSpeedRpm, String detail) {
    public enum Tier {
        EXISTING_HANDLE, FRESH_HANDLE, NONE
    }

    /** The motor was read back as standing still. */
    public boolean stopped() {
        return verified;
    }

    /**
     * De-energized and the drive is answering, but the shaft had not stopped yet. Expected on a
     * loaded rig, where the mass coasts for longer than the verify deadline; not an operator
     * emergency.
     */
    public boolean coasting() {
        return !verified && driveResponsive;
    }

    /**
     * No standstill could be confirmed for a motor that this run actually energized: the drive went
     * silent and the motor state is genuinely unknown. This is the only outcome that warrants
     * telling the operator to use the E-stop.
     * <p>
     * A run that died before energizing anything - a failed startup check, a setup() that threw
     * before its energize block - also reports {@code !stopped() && !coasting()}, because there was
     * no handle to answer. That is bookkeeping, not an incident, and must stay out of the loud path.
     */
    public boolean needsOperatorAttention() {
        return !verified && !driveResponsive && motorWasEnergized;
    }
}
