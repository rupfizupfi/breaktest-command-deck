package ch.rupfizupfi.deck.testrunner;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;

/**
 * Lifecycle state of ONE run, with the transition history that becomes its audit record.
 * <p>
 * One instance per run and deliberately not a Spring bean: the state belongs to the run, not to the
 * process, and a singleton would carry a previous run's history and listeners into the next one.
 * <p>
 * Written from several threads - the runner thread, the load-cell measurement thread on the loss
 * path, and an operator request thread on resume/abort - so all of it is thread safe.
 */
public class TestStateMachine {
    private static final Logger logger = LoggerFactory.getLogger(TestStateMachine.class);

    /**
     * One committed transition.
     *
     * @param sequence      monotonic, assigned under the state lock, so records carry the commit
     *                      order even when the fan-out that delivers them does not
     * @param reason        why it happened, in the words that reach the test log and the operator
     * @param actor         the operator behind the transition, null for the ones the machine or the
     *                      hardware made on its own
     */
    public record TransitionRecord(long sequence, TestState from, TestState to, long atEpochMillis,
                                   String reason, String actor) {
    }

    /**
     * Guards {@link #state}, {@link #sequence} and {@link #history}, and nothing else. Never held
     * while a listener runs, so no listener's I/O can delay a thread that needs to read or change
     * the state.
     */
    private final Object stateLock = new Object();

    /**
     * Serializes listener fan-out without holding {@link #stateLock}, so a listener is never
     * re-entered concurrently and cannot see its own callbacks interleave. Taken immediately after
     * the state lock is released; under contention two fan-outs can still reach it out of commit
     * order, which is exactly what {@link TransitionRecord#sequence()} exists to let a listener
     * repair.
     */
    private final Object notifyLock = new Object();

    /** Volatile for lock-free reads: several threads poll the state on their own loops. */
    private volatile TestState state = TestState.IDLE;

    /** Guarded by {@link #stateLock}. */
    private long sequence = 0;

    /** Guarded by {@link #stateLock}. */
    private final List<TransitionRecord> history = new ArrayList<>();

    private final List<TestStateListener> listeners = new CopyOnWriteArrayList<>();

    public TestState current() {
        return state;
    }

    public boolean transition(TestState to, String reason) {
        return apply(null, to, reason, null, null);
    }

    public boolean transition(TestState to, String reason, String actor) {
        return apply(null, to, reason, actor, null);
    }

    /**
     * Transitions only if the machine is still in {@code expected} and {@code guard} agrees, both
     * decided under the state lock.
     * <p>
     * That is what makes Resume safe: {@code canResume()} has to be evaluated under the same lock as
     * the compare-and-set, or a hold that just crossed its auto-abort deadline - or a second browser
     * that clicked Resume a millisecond earlier - slips between the check and the transition.
     *
     * @param guard evaluated under the state lock, so it must be cheap and must not do I/O or take
     *              another lock. A guard that throws is treated as a refusal.
     */
    public boolean compareAndTransition(TestState expected, TestState to, String reason, String actor,
                                        BooleanSupplier guard) {
        return apply(expected, to, reason, actor, guard);
    }

    public void addListener(TestStateListener listener) {
        listeners.add(listener);
    }

    /** Immutable copy: the caller usually serializes it while the run is still moving. */
    public List<TransitionRecord> history() {
        synchronized (stateLock) {
            return List.copyOf(history);
        }
    }

    private boolean apply(TestState expected, TestState to, String reason, String actor,
                          BooleanSupplier guard) {
        TransitionRecord record;

        synchronized (stateLock) {
            TestState from = state;

            // Every refusal below logs and returns false, never throws. This machine is driven from
            // the emergency path and from AbstractTest.cleanup(), which is documented as running
            // twice per test - so a duplicate terminal transition has to be a no-op, and an
            // exception raised while the motor is being stopped would mask the incident it was
            // raised about.
            if (expected != null && from != expected) {
                logger.warn("test state transition {} -> {} skipped, expected {} but the run is in {} ({})",
                        from, to, expected, from, reason);
                return false;
            }

            if (!from.canTransitionTo(to)) {
                logger.warn("illegal test state transition {} -> {} ignored ({})", from, to, reason);
                return false;
            }

            if (guard != null && !guardPasses(guard, from, to, reason)) {
                return false;
            }

            record = new TransitionRecord(++sequence, from, to, System.currentTimeMillis(), reason, actor);
            state = to;
            history.add(record);
        }

        logger.info("test state {} -> {} (#{}, {}{})", record.from(), record.to(), record.sequence(),
                reason, actor == null ? "" : ", by " + actor);
        notifyListeners(record);
        return true;
    }

    private boolean guardPasses(BooleanSupplier guard, TestState from, TestState to, String reason) {
        try {
            if (guard.getAsBoolean()) {
                return true;
            }
            logger.info("test state transition {} -> {} refused by its guard ({})", from, to, reason);
        } catch (Throwable t) {
            // A guard that blew up has not established the precondition, so the only safe reading is
            // "not allowed".
            logger.warn("test state transition {} -> {} refused, its guard threw ({})", from, to, reason, t);
        }
        return false;
    }

    private void notifyListeners(TransitionRecord record) {
        synchronized (notifyLock) {
            for (TestStateListener listener : listeners) {
                try {
                    listener.onTransition(record);
                } catch (Throwable t) {
                    // The transition is already committed and the remaining listeners - the
                    // broadcaster among them - still have to hear about it.
                    logger.warn("test state listener {} failed on transition {} -> {}",
                            listener.getClass().getName(), record.from(), record.to(), t);
                }
            }
        }
    }
}
