package ch.rupfizupfi.deck.testrunner;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;

/**
 * Lifecycle state of one run, with the transition history that becomes its audit record. One
 * instance per run, not a Spring bean. Thread safe: written by the runner, the measurement thread
 * and operator request threads.
 */
public class TestStateMachine {
    private static final Logger logger = LoggerFactory.getLogger(TestStateMachine.class);

    /**
     * One committed transition.
     *
     * @param sequence      monotonic, assigned under the state lock, so records carry the commit
     *                      order even when the fan-out that delivers them does not
     * @param reason        shown in the test log and to the operator
     * @param actor         the operator behind the transition, null when none
     */
    public record TransitionRecord(long sequence, TestState from, TestState to, long atEpochMillis,
                                   String reason, String actor) {
    }

    /**
     * Guards {@link #state}, {@link #sequence} and {@link #history}. Never held while a listener runs.
     */
    private final Object stateLock = new Object();

    /**
     * Serializes listener fan-out without holding {@link #stateLock}. Two fan-outs can still arrive
     * out of commit order; {@link TransitionRecord#sequence()} lets a listener repair that.
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
     * This keeps Resume from racing the auto-abort deadline or a second Resume.
     *
     * @param guard evaluated under the state lock, so it must be cheap and must not do I/O or take
     *              another lock. A guard that throws is treated as a refusal.
     */
    public boolean compareAndTransition(TestState expected, TestState to, String reason, String actor,
                                        BooleanSupplier guard) {
        return apply(expected, to, reason, actor, guard);
    }

    /**
     * Runs {@code action} only while the run is in {@code expected}, holding the state lock so no
     * transition can land between the check and the action. Same contract as a guard: cheap, no I/O.
     *
     * @return false, without running the action, when the run is in any other state
     */
    public boolean runWhileIn(TestState expected, Runnable action) {
        synchronized (stateLock) {
            if (state != expected) {
                return false;
            }
            action.run();
            return true;
        }
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

            // Refusals log and return false, never throw: this is driven from the emergency path and
            // from the run loop's teardown, which runs more than once.
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
                    // Already committed; the remaining listeners still have to hear about it.
                    logger.warn("test state listener {} failed on transition {} -> {}",
                            listener.getClass().getName(), record.from(), record.to(), t);
                }
            }
        }
    }
}
