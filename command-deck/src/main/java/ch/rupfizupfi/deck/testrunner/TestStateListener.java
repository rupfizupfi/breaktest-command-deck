package ch.rupfizupfi.deck.testrunner;

/**
 * Notified after a {@link TestStateMachine} transition has been committed.
 * <p>
 * Called outside the machine's state lock, so an implementation may persist or broadcast. It is
 * still called on the thread that made the transition — which on the loss path is the load-cell
 * measurement thread — so slow work belongs on an executor, not here. A listener that throws is
 * logged and skipped; it neither aborts the transition nor stops the remaining listeners.
 */
public interface TestStateListener {
    void onTransition(TestStateMachine.TransitionRecord record);
}
