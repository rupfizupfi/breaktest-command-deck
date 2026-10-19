package ch.rupfizupfi.deck.testrunner.run;

import ch.rupfizupfi.deck.testrunner.RecoveryGates;
import ch.rupfizupfi.deck.testrunner.program.ResumePolicy;

import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * The server's single Resume verdict for a standing hold, moved out of {@code AbstractTest#canResume}
 * (OQ-64 step 6). Evaluated under the state lock as the guard of
 * {@code compareAndTransition(SAFE_HOLD, RESUMING)}, so it stays cheap, does no I/O and takes no lock.
 * <p>
 * The incident facts it reads are written only by the run loop and read by request threads, hence
 * volatile. The resume gates themselves are owned by the "resume gates" section of
 * {@code doc/dev/backend/safety-and-recovery.md}.
 */
public final class ResumeGates {
    private final RecoveryGates gates;
    /** The run's test type's {@link ResumePolicy}; {@link ResumePolicy#ABORT_ONLY} until the program exists. */
    private final Supplier<ResumePolicy> policy;
    /**
     * A stop past tier 1 leaves the drive handle-less, so the run cannot re-energize until a new
     * run reconnects it.
     */
    private final BooleanSupplier driveAvailable;
    private final LongSupplier clock;

    /** Losses this run has already survived, this incident's included. */
    private volatile int lossCount = 0;
    /** Whether this incident's recovery gate passed. A new loss invalidates the previous verdict. */
    private volatile boolean gatePassed = false;
    /** When the current SAFE_HOLD began, epoch millis; 0 while no hold ever started. */
    private volatile long holdEnteredAtMillis = 0;

    ResumeGates(RecoveryGates gates, Supplier<ResumePolicy> policy, BooleanSupplier driveAvailable,
                LongSupplier clock) {
        this.gates = gates;
        this.policy = policy;
        this.driveAvailable = driveAvailable;
        this.clock = clock;
    }

    /** Loop thread: a loss began an incident. */
    void lossBegan() {
        lossCount++;
        gatePassed = false;
    }

    /** Loop thread: the hold the incident parks in began now. */
    void holdEntered() {
        holdEnteredAtMillis = clock.getAsLong();
    }

    /** Loop thread: the reconnector's verdict on this incident's stream. */
    void verdict(boolean passed) {
        gatePassed = passed;
    }

    int lossCount() {
        return lossCount;
    }

    /** Any thread; the guard of the Resume transition. */
    public boolean allowResume() {
        if (!gates.resumeEnabled() || !policy.get().supportsResume()) {
            return false;
        }
        if (!gatePassed) {
            return false;
        }
        if (lossCount > gates.maxLossesPerRun()) {
            return false;
        }
        if (clock.getAsLong() - holdEnteredAtMillis >= gates.maxHoldForResumeMillis()) {
            return false;
        }
        return driveAvailable.getAsBoolean();
    }
}
