package ch.rupfizupfi.deck.testrunner;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.util.function.Supplier;

/**
 * Pushes the run's state to the browser on every transition. One instance per run, like the
 * {@link TestStateMachine} it listens to. While an incident is open the run loop calls
 * {@link #rebroadcast()} on its own two-second tick, so a browser that reconnects mid-incident
 * converges; the broadcaster owns no thread or timer of its own (OQ-64 step 6).
 * <p>
 * The broker already routes {@code /topic/**}, so no WebSocket config change is involved.
 */
public class TestStateBroadcaster implements TestStateListener {
    private static final Logger logger = LoggerFactory.getLogger(TestStateBroadcaster.class);

    private static final String TOPIC = "/topic/test-state";

    private final SimpMessagingTemplate template;

    private final long testResultId;

    /** Provides the current snapshot; the broadcaster owns no run state of its own. */
    private final Supplier<TestStateMessage> snapshotSupplier;

    public TestStateBroadcaster(SimpMessagingTemplate template, long testResultId,
                                Supplier<TestStateMessage> snapshotSupplier) {
        this.template = template;
        this.testResultId = testResultId;
        this.snapshotSupplier = snapshotSupplier;
    }

    @Override
    public void onTransition(TestStateMachine.TransitionRecord record) {
        send();
    }

    /** The incident re-broadcast, from the run loop's tick. Never throws. */
    public void rebroadcast() {
        send();
    }

    /**
     * Never throws: it runs on the state fan-out path, often on the way to stopping the motor.
     */
    private void send() {
        TestStateMessage message;
        try {
            message = snapshotSupplier.get();
        } catch (Throwable t) {
            logger.warn("could not build the test state message for test result {}", testResultId, t);
            return;
        }

        if (message == null) {
            return;
        }

        try {
            template.convertAndSend(TOPIC, message);
        } catch (Throwable t) {
            logger.warn("could not broadcast the test state for test result {}", testResultId, t);
        }
    }
}
