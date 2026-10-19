package ch.rupfizupfi.deck.testrunner;

/**
 * Notified after a {@link TestStateMachine} transition has been committed.
 * <p>
 * Called outside the state lock but on the thread that made the transition, which on the loss path
 * is the measurement thread, so slow work belongs on an executor. A listener that throws is logged
 * and skipped.
 */
public interface TestStateListener {
    void onTransition(TestStateMachine.TransitionRecord record);
}
