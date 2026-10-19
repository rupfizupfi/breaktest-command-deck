package ch.rupfizupfi.deck.testrunner;

/**
 * What the browser is told about a run, on {@code /topic/test-state}.
 * <p>
 * A complete snapshot rather than a delta, so a browser reconnecting mid-incident can rebuild its
 * banner from one frame (see {@link TestStateBroadcaster}). Safety never depends on it arriving.
 *
 * @param canResume               the server's verdict; the UI must not compute its own
 * @param lastKnownForce          newton, null before the first measurement of the run
 * @param safeHoldDeadlineMillis  epoch millis for the browser's countdown, null outside SAFE_HOLD;
 *                                the server's own hold timer is a scheduled delay immune to clock steps
 * @param sequence                {@link TestStateMachine.TransitionRecord#sequence()}, so a client
 *                                can drop a frame that overtook a newer one
 */
public record TestStateMessage(TestState state, String reason, boolean canResume, int lossCount,
                               int reconnectAttempt, Float lastKnownForce,
                               Long safeHoldDeadlineMillis, long testResultId, long sequence) {
}
