package ch.rupfizupfi.deck.testrunner.program;

import ch.rupfizupfi.deck.testrunner.measure.RunEventSink;
import ch.rupfizupfi.deck.testrunner.run.Direction;
import ch.rupfizupfi.deck.testrunner.run.Limit;
import ch.rupfizupfi.deck.testrunner.run.RunControl;
import ch.rupfizupfi.deck.testrunner.run.RunEvent;
import ch.rupfizupfi.deck.testrunner.run.Verdict;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * The time-cyclic run phase: every turn, a crossing or a timed one, is the cyclic turn of
 * {@link CyclicTurns}, and after it this phase schedules the opposite turn - a release is followed
 * by a pull after the release time, a pull by a release after the pull time. The crossings still
 * turn the run too.
 * <p>
 * The scheduler is the phase's own and the only thread it starts. Its task does nothing but post
 * a {@link RunEvent.TimedTurn} to the sink, the measurement loop's turn gate, so a timed turn passes
 * the crossings' repeat filter; the loop acts on it only while RUNNING. {@link #close()} returns
 * only once no task is still in flight, so nothing this phase scheduled can reach the run after
 * it - the guarantee the hold entry relies on (I17).
 */
final class TimedCyclePhase implements Phase {
    private static final Logger logger = LoggerFactory.getLogger(TimedCyclePhase.class);

    /**
     * How long {@link #close()} waits for a task already running. A task is one non-blocking offer,
     * so this is never reached on a live JVM; it only bounds the loop thread's wait if it were.
     */
    static final long CLOSE_BOUND_MS = 1000;

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().name("time-cyclic-turns").daemon(true).factory());
    private final CyclicTurns turns;
    private final RunEventSink timedTurns;
    private final long releaseMs;
    private final long pullMs;
    private Verdict verdict;

    /**
     * @param verdict what the handing-over turn concluded; that turn is not timed, the next is
     */
    TimedCyclePhase(CyclicTurns turns, RunEventSink timedTurns, long releaseMs, long pullMs, Verdict verdict) {
        this.turns = turns;
        this.timedTurns = timedTurns;
        this.releaseMs = releaseMs;
        this.pullMs = pullMs;
        this.verdict = verdict;
    }

    @Override
    public Phase onLimit(Limit limit, RunControl control) {
        return onTurn(limit.turn(), control);
    }

    @Override
    public Phase onTimedTurn(Direction next, RunControl control) {
        return onTurn(next, control);
    }

    @Override
    public Verdict verdict() {
        return verdict;
    }

    /** The reversal first (see {@link CyclicTurns#onTurn}), then the opposite turn is scheduled. */
    private Phase onTurn(Direction turn, RunControl control) {
        verdict = turns.onTurn(turn, control);
        if (turn == Direction.RELEASE) {
            schedule(Direction.PULL, releaseMs);
        } else {
            schedule(Direction.RELEASE, pullMs);
        }
        return this;
    }

    private void schedule(Direction next, long delayMs) {
        try {
            scheduler.schedule(() -> timedTurns.accept(new RunEvent.TimedTurn(next)), delayMs, TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException e) {
            // Closed: the run is in a hold or ending, and the loop no longer asks this phase.
            logger.debug("timed turn to {} not scheduled, the phase is closed", next);
        }
    }

    /**
     * Drops every pending turn ({@code shutdownNow}, so none fires into a stopped or re-energizing
     * drive), then waits for a task already past its delay to finish its post, bounded by
     * {@link #CLOSE_BOUND_MS}.
     */
    @Override
    public void close() {
        scheduler.shutdownNow();
        try {
            if (!scheduler.awaitTermination(CLOSE_BOUND_MS, TimeUnit.MILLISECONDS)) {
                logger.warn("the timed-turn scheduler did not stop within {} ms", CLOSE_BOUND_MS);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** For tests: whether {@link #close()} completed. */
    boolean isClosed() {
        return scheduler.isTerminated();
    }
}
