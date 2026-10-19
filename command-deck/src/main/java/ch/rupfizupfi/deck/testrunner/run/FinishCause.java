package ch.rupfizupfi.deck.testrunner.run;

/** Why the run loop ended a run that had not thrown; handed to the program's finish hook. */
public enum FinishCause {
    /** A program's verdict on a limit crossing: the destructive test's stand-in for a break. */
    LIMIT,
    /** The cyclic types counted their last cycle. */
    CYCLES_DONE,
    /** An operator Stop. */
    OPERATOR,
    /** An operator Abort or the safe-hold timeout. */
    ABORT,
    /** A detector ended the run: a force-ceiling crossing or a dead measurement loop. */
    FAULT
}
