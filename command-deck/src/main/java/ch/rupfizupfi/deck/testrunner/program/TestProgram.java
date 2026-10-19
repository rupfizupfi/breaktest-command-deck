package ch.rupfizupfi.deck.testrunner.program;

import ch.rupfizupfi.deck.testrunner.run.Direction;
import ch.rupfizupfi.deck.testrunner.run.FinishCause;
import ch.rupfizupfi.deck.testrunner.run.Limit;
import ch.rupfizupfi.deck.testrunner.run.MotorStop;
import ch.rupfizupfi.deck.testrunner.run.RunControl;
import ch.rupfizupfi.deck.testrunner.run.Verdict;

/**
 * One test type's behaviour, declared rather than sequenced (OQ-64 step 7): its limits, how it
 * energizes, its answer to a crossing or a timed turn, and its part of a hold, a resume and the
 * end. The run loop owns the one start sequence, every state transition, the motor stops and the
 * incident path; a program only answers the loop's calls, all on the loop thread, and acts on the
 * run only through {@link RunControl}.
 * <p>
 * One instance per run, from {@link TestProgramRegistry#create}.
 */
public interface TestProgram {

    /** The type as the run log's closing line has always named it. */
    String name();

    /** The run's limits and ceiling at start, newton. */
    Limits limits();

    /** How the STARTING entry energizes the drive. */
    DriveSetup startup();

    /**
     * The type's run-log lines, after the measurement loop started and before the first-sample
     * wait. Nothing else: the energize follows that wait.
     */
    default void onStart(RunControl control) {
    }

    /** A crossing of one of the run's limits, while the run is RUNNING. */
    Verdict onLimit(Limit limit, RunControl control);

    /** A timed turn; only the time-cyclic run phase schedules them. */
    default Verdict onTimedTurn(Direction next, RunControl control) {
        control.log("ignoring an unexpected timed turn to " + next);
        return Verdict.CONTINUE;
    }

    /**
     * Whether a sensor-loss hold of this type may end in a resume, and how it re-energizes (OQ-64
     * step 8). Read by {@code ResumeGates} on request threads, so it returns the same value for the
     * whole run.
     */
    ResumePolicy resumePolicy();

    /**
     * Type-specific work at the start of a hold, before SAFE_HOLD is committed. The motor is already
     * stopped; an override must not wait on the drive.
     */
    default void onHoldEntry() {
    }

    /**
     * After every motor stop of the run's teardown. Runs more than once per run, so it must be
     * idempotent; a drive call here may fail on a handle the stop closed.
     */
    default void teardown(RunControl control) {
    }

    /**
     * After the loop stopped the motor and tore down; {@code cause} is the run's, not the verdict's,
     * and {@code stop} is how that finish stop went.
     */
    default void onFinished(FinishCause cause, MotorStop stop, RunControl control) {
    }
}
