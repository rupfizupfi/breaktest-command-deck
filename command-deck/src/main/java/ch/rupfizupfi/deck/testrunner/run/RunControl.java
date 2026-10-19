package ch.rupfizupfi.deck.testrunner.run;

import ch.rupfizupfi.deck.testrunner.measure.RunEventSink;
import ch.rupfizupfi.deck.testrunner.program.Limits;
import ch.rupfizupfi.deck.testrunner.program.Ramp;
import jakarta.annotation.Nullable;

/**
 * What a {@code TestProgram} may do to its run (OQ-64 step 7). {@link RunLoop} implements it; every
 * call is made on the loop thread, from inside a program hook. The energize itself is not here: a
 * program declares a {@code DriveSetup} and the loop applies it.
 */
public interface RunControl {

    /** The measured envelope as the measurement loop folded it; a reset posted but not yet applied reads as applied. */
    record Envelope(float min, float max) {
    }

    /**
     * One reversal: one Modbus write, no read-back. Refused with a log line while a motor stop is
     * latched, and fenced on the drive's stop epoch, so no reversal can follow a stop.
     *
     * @return false when refused; the commanded direction then keeps what the drive was last told
     */
    boolean turn(Direction direction);

    /**
     * The direction last commanded - by an energize or an accepted {@link #turn} - rather than read
     * back: the reversal decision is a dedup against our own command, not a safety check (H-05).
     * Null before the first energize.
     */
    @Nullable Direction direction();

    /** A running drive's new speed and ramp, without touching start or enable; null ramp turns the second ramp off. */
    void runPhaseSpeed(int rpm, @Nullable Ramp ramp);

    /**
     * Switches the second ramp off. For the teardown, after the motor stop, so it never comes
     * ahead of the de-energize; throws when the drive cannot take it.
     */
    void disableSecondRamp();

    Envelope envelope();

    /** Posts a reset of the envelope minimum; the measurement loop applies it before its next sample. */
    void resetEnvelopeMin(float value);

    /** Posts a reset of the envelope maximum; the measurement loop applies it before its next sample. */
    void resetEnvelopeMax(float value);

    /** Last force the watchdog saw, {@link Float#NaN} when none. */
    float lastForce();

    /** The limits in force, as last adapted. */
    Limits limits();

    /** Moves both limits; the ceiling stays. Refuses a non-finite value (I13). */
    void adaptLimits(double upper, double lower);

    /**
     * Where a timed turn enters the run: through the crossings' repeat filter, so a timed turn
     * re-arms the opposite edge and a repeat of the last one is dropped. Any thread.
     */
    RunEventSink timedTurns();

    /** One line of the run log. */
    void log(String line);
}
