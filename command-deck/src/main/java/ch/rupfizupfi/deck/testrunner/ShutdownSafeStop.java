package ch.rupfizupfi.deck.testrunner;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Stops the motor when the application context closes: SIGTERM, Ctrl+C, a Windows shutdown or
 * logoff, a {@code taskkill} without {@code /F}, devtools restart. Highest phase, so it stops before
 * every other lifecycle bean, the web server and the message broker included.
 * <p>
 * Whether a run is active or the motor is energized is not observable from a bean: both live in
 * {@code TestRunnerThread} and {@code MotorSafetyController} privately. An open drive handle is the
 * observable superset - {@code MotorSafetyController#energize} runs only over the device's open
 * handle - so that decides between a full {@code safeStop} and a latch without hardware. The latch
 * still makes any later energize of a run in setup refuse.
 * <p>
 * No JVM shutdown hook of its own: Spring Boot's shutdown hook is what closes the context on every
 * signal a JVM hook would see, so a second hook would only run this concurrently with the context
 * teardown. What neither reaches - {@code taskkill /F}, a JVM crash, power loss - is the drive's own
 * link-loss stop's job, armed at energize.
 */
@Component
public class ShutdownSafeStop implements SmartLifecycle {

    private static final Logger logger = LoggerFactory.getLogger(ShutdownSafeStop.class);

    static final String REASON = "context shutdown";

    /**
     * Above safeStop's own worst case short of a wedged native call (two lock waits, two 5 s
     * verifications), so only a wedge is cut off; shutdown then goes on without it.
     */
    static final long STOP_BOUND_MS = 15_000;

    private final MotorSafetyController motorSafetyController;
    private final long stopBoundMs;

    private volatile boolean running;
    private final AtomicBoolean stopped = new AtomicBoolean(false);

    @Autowired
    public ShutdownSafeStop(MotorSafetyController motorSafetyController) {
        this(motorSafetyController, STOP_BOUND_MS);
    }

    ShutdownSafeStop(MotorSafetyController motorSafetyController, long stopBoundMs) {
        this.motorSafetyController = motorSafetyController;
        this.stopBoundMs = stopBoundMs;
    }

    @Override
    public void start() {
        running = true;
    }

    @Override
    public void stop() {
        running = false;
        if (!stopped.compareAndSet(false, true)) {
            return;
        }

        if (!motorSafetyController.isDriveAvailable()) {
            // No handle, so nothing can be energized; the latch keeps a run in setup from energizing.
            motorSafetyController.latchStop(REASON);
            return;
        }

        var result = new AtomicReference<SafeStopResult>();
        // Daemon: a safeStop wedged in a native call must not keep the JVM alive past shutdown.
        Thread worker = Thread.ofPlatform()
                .daemon()
                .name("shutdown-safe-stop")
                .start(() -> result.set(motorSafetyController.safeStop(REASON)));
        try {
            worker.join(stopBoundMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        SafeStopResult stop = result.get();
        if (stop == null) {
            logger.error("the motor stop on shutdown did not finish within {} ms, the drive may still be"
                    + " energized - USE THE PHYSICAL E-STOP", stopBoundMs);
        } else if (stop.needsOperatorAttention()) {
            logger.error("the motor stop on shutdown could not be verified: {}", stop.detail());
        } else {
            logger.info("motor stopped for shutdown: {}", stop.detail());
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public int getPhase() {
        return Integer.MAX_VALUE;
    }
}
