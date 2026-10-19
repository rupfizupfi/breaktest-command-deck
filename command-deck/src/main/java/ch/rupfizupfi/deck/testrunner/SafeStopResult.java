package ch.rupfizupfi.deck.testrunner;

/**
 * Outcome of a {@link MotorSafetyController#safeStop(String)} attempt.
 *
 * @param tier              the highest tier that was reached
 * @param verified          true only if the motor was read back as standing still
 * @param driveResponsive   true if the drive's control word read back general enable and start clear
 *                          and it answered at least one speed read, i.e. the USB handle demonstrably
 *                          works and the de-energize landed — judged by that read-back, not by
 *                          whether the first stop write succeeded
 * @param motorWasEnergized true if this run had genuinely enabled the drive output stage before the
 *                          stop, i.e. there was a live motor for the stop to catch
 * @param motorSpeedRpm     last measured motor speed, or null if none could be read
 * @param detail            human readable trace for the test log
 */
public record SafeStopResult(Tier tier, boolean verified, boolean driveResponsive,
                             boolean motorWasEnergized, Integer motorSpeedRpm, String detail) {
    public enum Tier {
        /** Tier 1 produced the verdict: the handle the run already held. */
        EXISTING_HANDLE,
        /** Tier 2 produced the verdict: a re-enumerated drive handle. */
        FRESH_HANDLE,
        /** Both software tiers ran and could not verify a stop; nothing is left to command. */
        OPERATOR_ESCALATION,
        /** No tier ran: the dispatch itself failed. */
        NONE
    }

    /**
     * De-energized and the drive is answering, but the shaft had not stopped yet. Expected on a
     * loaded rig; not an operator emergency.
     */
    public boolean coasting() {
        return !verified && driveResponsive;
    }

    /**
     * No standstill confirmed for a motor this run energized, with the drive silent: the only outcome
     * that warrants the E-stop. A run that never energized also reports
     * {@code !verified() && !coasting()} but is excluded.
     */
    public boolean needsOperatorAttention() {
        return !verified && !driveResponsive && motorWasEnergized;
    }
}
