package ch.rupfizupfi.deck.testrunner;

import ch.rupfizupfi.deck.device.loadcell.LoadCellDevice;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Rebuilds a lost load-cell connection and decides whether the stream that comes back may be
 * trusted. One instance per run, like the {@link TestStateMachine} whose SENSOR_LOST it serves.
 * <p>
 * The policy - backoff, window, gates - is owned by
 * {@code doc/06-feature-work/testrunner-safety/loadcell-recovery-design.md}; this class is where the
 * two driver facts in it become code: a stopped stream can never be restarted (so every attempt goes
 * through {@link ch.rupfizupfi.deck.device.Device#reset()}, which builds a new one), and
 * {@code Connection.open()} fails inside the driver's own spawned thread (so a reconnect is judged
 * by fresh DATA, never by a call that returned).
 */
public class SensorReconnector {
    private static final Logger logger = LoggerFactory.getLogger(SensorReconnector.class);

    /**
     * Longest single wait for data after one reset. Bounds an attempt rather than the window, so a
     * generous window still buys several resets instead of one long stare at a dead port.
     */
    private static final long FRESH_DATA_SLICE_MS = 1000;

    /**
     * Headroom on top of {@code plausibilityGateMillis} for the gate's future to come back. The gate
     * is collected by the measurement thread, which has a whole batch to write before it completes.
     */
    private static final long GATE_TIMEOUT_MARGIN_MS = 2000;

    /**
     * @param attempts how many resets were made, so a run that recovered on the first try and one
     *                 that recovered on the fifth are distinguishable in the incident record
     * @param gate     the plausibility verdict, null when no attempt ever produced data to gate
     */
    public record Outcome(boolean recovered, int attempts, String detail, LoadCellThread.GateResult gate) {
    }

    private final LoadCellDevice loadCellDevice;
    private final LoadCellThread loadCellThread;
    private final RecoveryProperties recovery;

    /**
     * The loop's own thread, and deliberately neither of the two threads that could otherwise host
     * it: not the measurement thread, which has to stay parked and responsive for the moment data
     * comes back, and not the run's control executor, which has to keep firing the SAFE_HOLD
     * auto-abort timer while a reconnect is occupying its whole window. Owning the thread is also
     * what makes {@link #shutdownNow()} able to cut a reconnect loose that is wedged in a native
     * reset; a plain method call on a borrowed thread could not be cancelled.
     */
    private final ExecutorService executor =
            Executors.newSingleThreadExecutor(r -> new Thread(r, "sensor-reconnector"));

    public SensorReconnector(LoadCellDevice loadCellDevice, LoadCellThread loadCellThread,
                             RecoveryProperties recovery) {
        this.loadCellDevice = loadCellDevice;
        this.loadCellThread = loadCellThread;
        this.recovery = recovery;
    }

    /**
     * Retries until the sensor delivers gated data or the reconnect window expires. Never throws:
     * every failure is an {@link Outcome} whose {@code detail} is what the operator gets told.
     *
     * @param envelopeNewton the test's force envelope, which the drift gate is a fraction of
     */
    public Outcome attemptRecovery(String lossReason, float lastKnownForce, double envelopeNewton) {
        Future<Outcome> pending;
        try {
            pending = executor.submit(() -> runAttempts(lossReason, lastKnownForce, envelopeNewton));
        } catch (RejectedExecutionException e) {
            return new Outcome(false, 0, "the reconnector was already shut down", null);
        }

        try {
            return pending.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            pending.cancel(true);
            return new Outcome(false, 0, "recovery abandoned, the waiting thread was interrupted", null);
        } catch (ExecutionException e) {
            // runAttempts catches per attempt, so this is a defect rather than a hardware failure -
            // but the run still has to be told something instead of hanging on a lost verdict.
            logger.error("load cell recovery failed unexpectedly", e.getCause());
            return new Outcome(false, 0, "recovery failed unexpectedly: " + e.getCause(), null);
        }
    }

    /** Releases the reconnect thread; a reconnect in flight is interrupted. Call it from run teardown. */
    public void shutdownNow() {
        executor.shutdownNow();
    }

    private Outcome runAttempts(String lossReason, float lastKnownForce, double envelopeNewton) {
        // nanoTime, never currentTimeMillis: an NTP step or a manual clock change on the bench
        // machine must not stretch or shorten a recovery window.
        long deadline = System.nanoTime()
                + TimeUnit.MILLISECONDS.toNanos(recovery.getReconnectWindowMillis());
        List<Long> backoff = recovery.getBackoffMillis();
        int attempts = 0;
        String detail = "no fresh measurement before the reconnect window expired";

        logger.warn("load cell recovery started ({}), last known force {} N, window {} ms",
                lossReason, lastKnownForce, recovery.getReconnectWindowMillis());

        while (true) {
            if (attempts > 0 && !sleepBeforeAttempt(backoff, attempts, deadline)) {
                break;
            }
            if (remainingMillis(deadline) <= 0) {
                break;
            }

            attempts++;
            try {
                loadCellDevice.reset();
            } catch (Throwable t) {
                // Throwable: a mismatched dscusb jar surfaces as a linkage Error, and that must end
                // this attempt rather than the recovery.
                detail = "attempt " + attempts + ": reset failed: " + t;
                logger.warn("load cell reset attempt {} failed", attempts, t);
                continue;
            }

            long slice = Math.min(remainingMillis(deadline), FRESH_DATA_SLICE_MS);
            if (slice <= 0 || !loadCellDevice.awaitFreshMeasurement(slice)) {
                detail = "attempt " + attempts + ": the device re-opened but delivered no measurement";
                continue;
            }

            loadCellThread.markSensorRecovered();
            LoadCellThread.GateResult gate;
            try {
                gate = loadCellThread.beginRecoveryGate(lastKnownForce, envelopeNewton)
                        .get(recovery.getPlausibilityGateMillis() + GATE_TIMEOUT_MARGIN_MS,
                                TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return new Outcome(false, attempts, "recovery interrupted while gating the stream", null);
            } catch (ExecutionException | TimeoutException e) {
                // The stream came back but the gate never produced a verdict, which is the same
                // situation as a failed gate: nothing has established that the data is usable.
                return new Outcome(false, attempts,
                        "attempt " + attempts + ": the plausibility gate produced no verdict: " + e, null);
            }

            if (!gate.passed()) {
                // TERMINAL for the resume offer, and not retried. A sensor that reconnects and
                // reports garbage is exactly the case where resuming would corrupt the run: another
                // reset would at best produce the same garbage, at worst produce a plausible-looking
                // reading from a cell that is no longer measuring the specimen.
                logger.error("load cell reconnected on attempt {} but failed its plausibility gate: {}",
                        attempts, gate.detail());
                return new Outcome(false, attempts,
                        "the reconnected sensor failed its plausibility gate: " + gate.detail(), gate);
            }

            logger.warn("load cell recovered on attempt {}: {}", attempts, gate.detail());
            return new Outcome(true, attempts, "recovered on attempt " + attempts, gate);
        }

        logger.error("load cell recovery gave up after {} attempt(s): {}", attempts, detail);
        return new Outcome(false, attempts, detail, null);
    }

    /**
     * Sleeps the backoff for the attempt about to be made, clamped to whatever is left of the
     * window. The last configured entry repeats, so a window longer than the list keeps retrying at
     * the slowest rate rather than falling back to a hot loop.
     *
     * @return false when the window is over or the sleep was interrupted, i.e. do not attempt again
     */
    private boolean sleepBeforeAttempt(List<Long> backoff, int attemptsSoFar, long deadline) {
        long remaining = remainingMillis(deadline);
        if (remaining <= 0) {
            return false;
        }

        long delay = backoff.isEmpty()
                ? FRESH_DATA_SLICE_MS
                : backoff.get(Math.min(attemptsSoFar - 1, backoff.size() - 1));

        try {
            Thread.sleep(Math.min(delay, remaining));
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static long remainingMillis(long deadlineNanos) {
        return TimeUnit.NANOSECONDS.toMillis(deadlineNanos - System.nanoTime());
    }
}
