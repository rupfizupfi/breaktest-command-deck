package ch.rupfizupfi.deck.testrunner.program;

import ch.rupfizupfi.deck.testrunner.run.Direction;
import ch.rupfizupfi.deck.testrunner.run.Limit;
import ch.rupfizupfi.deck.testrunner.run.RunControl;
import ch.rupfizupfi.deck.testrunner.run.Verdict;

/**
 * One phase of a phased program (OQ-64 step 8): the time-cyclic {@link CalibrationPhase} and
 * {@link TimedCyclePhase}. Each answer returns the phase the program continues in, itself or its
 * successor, whose {@link #verdict()} is that answer's verdict. Loop thread only, so a phase needs
 * no lock: the program swaps phases between two loop calls and nothing else sees them.
 */
interface Phase {

    /** A crossing, while the run is RUNNING. */
    Phase onLimit(Limit limit, RunControl control);

    /** A timed turn, while the run is RUNNING. */
    Phase onTimedTurn(Direction next, RunControl control);

    /** What the call that returned this phase concluded. */
    Verdict verdict();

    /**
     * Ends the phase for good. After it returns, nothing the phase started can reach the run any
     * more. Idempotent.
     */
    void close();
}
