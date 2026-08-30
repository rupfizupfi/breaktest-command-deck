package ch.rupfizupfi.deck.testrunner;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Pushes the run's state to the browser on every transition, and keeps pushing it while an incident
 * is open. One instance per run, like the {@link TestStateMachine} it listens to.
 * <p>
 * The broker already routes {@code /topic/**}, so no WebSocket config change is involved.
 */
public class TestStateBroadcaster implements TestStateListener {
    private static final Logger logger = LoggerFactory.getLogger(TestStateBroadcaster.class);

    private static final String TOPIC = "/topic/test-state";

    /** Re-broadcast period during an incident, so a browser that reconnects mid-incident converges. */
    private static final long INCIDENT_REBROADCAST_MS = 2000;

    private final SimpMessagingTemplate template;

    /** Supplied by the run's owner, never created here: one run must not spawn its own thread pool. */
    private final ScheduledExecutorService scheduler;

    private final long testResultId;

    /** Provides the current snapshot; the broadcaster owns no run state of its own. */
    private final Supplier<TestStateMessage> snapshotSupplier;

    /** Guards {@link #ticker}. Both the fan-out thread and {@link #stop()} touch it. */
    private final Object tickerLock = new Object();

    private ScheduledFuture<?> ticker;

    public TestStateBroadcaster(SimpMessagingTemplate template, ScheduledExecutorService scheduler,
                                long testResultId, Supplier<TestStateMessage> snapshotSupplier) {
        this.template = template;
        this.scheduler = scheduler;
        this.testResultId = testResultId;
        this.snapshotSupplier = snapshotSupplier;
    }

    @Override
    public void onTransition(TestStateMachine.TransitionRecord record) {
        // Cancelling on every non-incident state - which covers both leaving an incident and every
        // terminal state - is what keeps this from leaking one repeating task per run onto a
        // scheduler that outlives the run.
        if (record.to().isIncident()) {
            startTicker();
        } else {
            stop();
        }
        send();
    }

    /**
     * Cancels the incident ticker. Idempotent; call it from the run's teardown as well, so a run
     * that ends without ever reaching a terminal transition still releases its scheduled task.
     */
    public void stop() {
        synchronized (tickerLock) {
            if (ticker != null) {
                ticker.cancel(false);
                ticker = null;
            }
        }
    }

    private void startTicker() {
        synchronized (tickerLock) {
            // SENSOR_LOST -> SAFE_HOLD stays inside one incident; restarting the ticker there would
            // reset the period for no gain.
            if (ticker != null) {
                return;
            }
            try {
                // Fixed DELAY, not fixed rate: a slow broker must not leave a backlog of frames
                // queued to fire back to back once it recovers.
                ticker = scheduler.scheduleWithFixedDelay(this::send, INCIDENT_REBROADCAST_MS,
                        INCIDENT_REBROADCAST_MS, TimeUnit.MILLISECONDS);
            } catch (Throwable t) {
                // A rejected task costs the browser its periodic refresh, nothing more - the
                // per-transition sends still go out, and the safe hold is timed server side.
                logger.warn("could not schedule the incident re-broadcast for test result {}",
                        testResultId, t);
            }
        }
    }

    /**
     * Never throws: this runs on the state fan-out path, where the calling thread is usually on its
     * way to stopping the motor.
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
