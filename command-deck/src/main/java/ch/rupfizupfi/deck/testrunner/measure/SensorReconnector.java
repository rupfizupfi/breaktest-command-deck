package ch.rupfizupfi.deck.testrunner.measure;

import ch.rupfizupfi.deck.testrunner.RecoveryGates;
import ch.rupfizupfi.deck.device.loadcell.LoadCellDevice;
import ch.rupfizupfi.deck.testrunner.run.RunEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Rebuilds a lost load-cell connection and decides whether the stream that comes back may be
 * trusted. One instance per run, like the {@code TestStateMachine} whose SENSOR_LOST it serves.
 * <p>
 * The policy - backoff, window, gates - is owned by
 * {@code doc/dev/backend/safety-and-recovery.md}. A stopped stream cannot
 * be restarted, so every attempt goes through {@link ch.rupfizupfi.deck.device.Device#reset()}; and
 * {@code Connection.open()} fails inside the driver's own thread, so a reconnect is judged by fresh
 * data, never by a call that returned.
 */
public class SensorReconnector {
    private static final Logger logger = LoggerFactory.getLogger(SensorReconnector.class);

    /**
     * Longest single wait for data after one reset, so a long window still buys several resets.
     */
    private static final long FRESH_DATA_SLICE_MS = 1000;

    /**
     * Headroom on top of {@code plausibilityGateMillis} for the gate's future to come back. The gate
     * is collected by the measurement thread, which has a whole batch to write before it completes.
     */
    private static final long GATE_TIMEOUT_MARGIN_MS = 2000;

    /**
     * @param attempts how many resets were made
     * @param gate     the plausibility verdict, null when no attempt ever produced data to gate
     */
    public record Outcome(boolean recovered, int attempts, String detail, MeasurementLoop.GateResult gate) {
    }

    private final LoadCellDevice loadCellDevice;
    private final MeasurementLoop measurementLoop;
    private final RecoveryGates gates;

    /**
     * Its own thread, so neither the measurement thread nor the run loop (which serves the hold's
     * deadline, Stop and Abort) is occupied, and {@link #shutdownNow()} can cut loose a reset wedged
     * in native code.
     */
    private final ExecutorService executor =
            Executors.newSingleThreadExecutor(r -> new Thread(r, "sensor-reconnector"));

    public SensorReconnector(LoadCellDevice loadCellDevice, MeasurementLoop measurementLoop,
                             RecoveryGates gates) {
        this.loadCellDevice = loadCellDevice;
        this.measurementLoop = measurementLoop;
        this.gates = gates;
    }

    /**
     * The SAFE_HOLD entry action's half: runs {@link #attemptRecovery} on a worker of its own, which
     * reports the verdict to the run loop as a {@link RunEvent.Recovered}. Returns at once.
     *
     * @param sink       the run loop
     * @param threadName names the worker after the run
     */
    public void startRecovery(String lossReason, float lastKnownForce, double envelopeNewton,
                              RunEventSink sink, String threadName) {
        Thread worker = new Thread(() -> {
            Outcome outcome;
            try {
                outcome = attemptRecovery(lossReason, lastKnownForce, envelopeNewton);
            } catch (Throwable t) {
                // attemptRecovery never throws; a defect still ends in a verdict the hold can abort on.
                logger.error("sensor recovery failed", t);
                outcome = new Outcome(false, 0, "recovery failed unexpectedly: " + t, null);
            }
            try {
                sink.accept(new RunEvent.Recovered(outcome));
            } catch (Throwable t) {
                logger.error("could not hand the sensor recovery verdict to the run", t);
            }
        }, threadName);
        worker.setDaemon(true);
        worker.start();
    }

    /**
     * Retries until the sensor delivers gated data or the reconnect window expires. Never throws:
     * every failure is an {@link Outcome} whose {@code detail} the operator is shown. The wait is
     * bounded by {@link #verdictBoundMillis()}, and {@link #shutdownNow()} releases a queued caller.
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

        long verdictBound = verdictBoundMillis();
        try {
            return pending.get(verdictBound, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            pending.cancel(true);
            return new Outcome(false, 0, "recovery abandoned, the waiting thread was interrupted", null);
        } catch (CancellationException e) {
            return new Outcome(false, 0, "recovery abandoned, the run was torn down", null);
        } catch (TimeoutException e) {
            pending.cancel(true);
            logger.error("load cell recovery produced no verdict within {} ms", verdictBound);
            return new Outcome(false, 0, "recovery produced no verdict within " + verdictBound
                    + " ms, the reconnect thread is wedged", null);
        } catch (ExecutionException e) {
            // runAttempts catches per attempt, so this is a defect rather than a hardware failure.
            logger.error("load cell recovery failed unexpectedly", e.getCause());
            return new Outcome(false, 0, "recovery failed unexpectedly: " + e.getCause(), null);
        }
    }

    /**
     * Releases the reconnect thread; a reconnect in flight is interrupted and a queued attempt is
     * cancelled, so its caller gets a verdict. Call it from run teardown.
     */
    public void shutdownNow() {
        for (Runnable drained : executor.shutdownNow()) {
            if (drained instanceof Future<?> queued) {
                queued.cancel(false);
            }
        }
    }

    /**
     * The window plus one attempt's worst case: a reset call allowed {@link #GATE_TIMEOUT_MARGIN_MS},
     * one {@link #FRESH_DATA_SLICE_MS} wait for data, and one gate wait of its own margin.
     */
    private long verdictBoundMillis() {
        return gates.reconnectWindowMillis() + FRESH_DATA_SLICE_MS + gates.plausibilityGateMillis()
                + 2 * GATE_TIMEOUT_MARGIN_MS;
    }

    private Outcome runAttempts(String lossReason, float lastKnownForce, double envelopeNewton) {
        // nanoTime, so a wall-clock step cannot skew the recovery window.
        long deadline = System.nanoTime()
                + TimeUnit.MILLISECONDS.toNanos(gates.reconnectWindowMillis());
        List<Long> backoff = gates.backoffMillis();
        int attempts = 0;
        String detail = "no fresh measurement before the reconnect window expired";

        logger.warn("load cell recovery started ({}), last known force {} N, window {} ms",
                lossReason, lastKnownForce, gates.reconnectWindowMillis());

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

            measurementLoop.markSensorRecovered();
            MeasurementLoop.GateResult gate;
            try {
                gate = measurementLoop.beginRecoveryGate(lastKnownForce, envelopeNewton)
                        .get(gates.plausibilityGateMillis() + GATE_TIMEOUT_MARGIN_MS,
                                TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return new Outcome(false, attempts, "recovery interrupted while gating the stream", null);
            } catch (ExecutionException | TimeoutException e) {
                // No verdict counts as a failed gate.
                return new Outcome(false, attempts,
                        "attempt " + attempts + ": the plausibility gate produced no verdict: " + e, null);
            }

            if (!gate.passed()) {
                // Terminal, not retried: another reset could yield a plausible-looking reading from
                // a cell that no longer measures the specimen.
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
     * window. The last configured entry repeats.
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
