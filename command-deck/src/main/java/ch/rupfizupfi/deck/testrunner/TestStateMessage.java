package ch.rupfizupfi.deck.testrunner;

/**
 * What the browser is told about a run, on {@code /topic/test-state}.
 * <p>
 * A complete snapshot rather than a delta: a browser that reconnects mid-incident gets one of these
 * every 2 s (see {@link TestStateBroadcaster}) and must be able to rebuild its banner from a single
 * frame. Safety never depends on this message arriving.
 *
 * @param canResume               the server's verdict, already gated - the UI enables its Resume
 *                                button from this and must not compute its own
 * @param lastKnownForce          newton, null before the first measurement of the run
 * @param safeHoldDeadlineMillis  epoch millis, null outside SAFE_HOLD. Epoch FOR THE BROWSER, which
 *                                needs a wall clock to render a countdown. The server's own hold
 *                                timer runs on {@code nanoTime} instead, so an NTP step or a manual
 *                                clock change can neither shorten a safe hold nor extend one; the
 *                                two clocks are deliberate, not a duplication to be tidied away.
 * @param sequence                {@link TestStateMachine.TransitionRecord#sequence()}, so a client
 *                                can drop a frame that overtook a newer one
 */
public record TestStateMessage(TestState state, String reason, boolean canResume, int lossCount,
                               int reconnectAttempt, Float lastKnownForce,
                               Long safeHoldDeadlineMillis, long testResultId, long sequence) {
}
