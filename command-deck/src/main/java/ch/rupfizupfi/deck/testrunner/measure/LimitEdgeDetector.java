package ch.rupfizupfi.deck.testrunner.measure;

import ch.rupfizupfi.deck.testrunner.run.Direction;
import ch.rupfizupfi.deck.testrunner.run.Limit;
import ch.rupfizupfi.deck.testrunner.run.RunEvent;

import java.util.concurrent.atomic.AtomicReference;

/**
 * Turns the measured force into limit crossings: {@link Limit#UPPER} once when the force is above
 * the upper limit, then not again until a {@link Direction#PULL} edge or a {@link #reset()};
 * symmetric for the lower limit. A non-finite sample decides nothing, because both comparisons are
 * false for NaN and an unguarded check would read it as inside both limits.
 * <p>
 * The per-drain rule: the first out-of-range sample of a drain decides the drain, and the decision
 * is sent only after the whole drain has been inspected, so a ceiling trip or a sensor fault later
 * in the same drain still suppresses it ({@link #discard()}). Measurement thread only, except
 * {@link #reset()} and {@link #admit(Direction)}.
 * <p>
 * The repeat filter is shared with the time-cyclic test's timed turns, which enter the run through
 * {@link #admit(Direction)} as the old int queue let them in: a timed PULL re-arms
 * the upper edge, and a timed turn repeating the last edge is dropped. Without the first a
 * time-cyclic run would lose its upper-limit reversal after the first timed turn; without the
 * second a turn would be scheduled twice.
 */
final class LimitEdgeDetector {
    private final ForceLimits context;
    private final RunEventSink sink;

    /** The last turn let into the run, null when re-armed both ways. Any thread. */
    private final AtomicReference<Direction> lastTurn = new AtomicReference<>();

    // Drain state, measurement thread only.
    private Limit pending;
    private float pendingForce;

    LimitEdgeDetector(ForceLimits context, RunEventSink sink) {
        this.context = context;
        this.sink = sink;
    }

    /** One sample of the current drain; only the drain's first out-of-range sample counts. */
    void observe(float force) {
        if (pending != null || !Float.isFinite(force)) {
            return;
        }
        if (force > context.getUpperLimit()) {
            pending = Limit.UPPER;
            pendingForce = force;
        } else if (force < context.getLowerLimit()) {
            pending = Limit.LOWER;
            pendingForce = force;
        }
    }

    /** Ends the drain: sends its decision unless it repeats the last turn. */
    void emit() {
        Limit crossed = pending;
        float force = pendingForce;
        discard();
        if (crossed != null && admit(crossed.turn())) {
            sink.accept(new RunEvent.LimitCrossed(crossed, force));
        }
    }

    /** Ends the drain without sending: the drain ended in a trip, or the stream is not trusted. */
    void discard() {
        pending = null;
    }

    /** Re-arms both edges, so the next crossing is sent even if it repeats the last one. */
    void reset() {
        lastTurn.set(null);
    }

    /**
     * The shared repeat filter: lets {@code turn} into the run unless it repeats the last one, and
     * makes it the last one. Atomic, since crossings and timed turns arrive on different threads.
     */
    boolean admit(Direction turn) {
        Direction previous;
        do {
            previous = lastTurn.get();
            if (previous == turn) {
                return false;
            }
        } while (!lastTurn.compareAndSet(previous, turn));
        return true;
    }
}
