package ch.rupfizupfi.deck.testrunner.run;

/**
 * How the motor stop that ended a run went, as a program may see it: the loop maps the first
 * finish stop's {@code SafeStopResult} onto this, so a program never reaches the runner's stop
 * values. Handed to {@code TestProgram#onFinished}, the one point it is known and meaningful.
 */
public enum MotorStop {
    /** Read back as standing still. */
    CONFIRMED,
    /** De-energized and the drive answering, the shaft still turning: {@code SafeStopResult#coasting()}. */
    COASTING,
    /**
     * No standstill confirmed for a motor this run energized, with the drive silent: the shaft may
     * pull on until the drive's own link-loss stop. {@code SafeStopResult#needsOperatorAttention()}.
     */
    UNCONFIRMED,
    /**
     * No standstill confirmed and the drive silent, but this run never energized the motor, so
     * there was nothing to stop. A limit crossing only reaches a program in RUNNING, after the
     * energize, so a destructive break never sees this.
     */
    NEVER_ENERGIZED
}
