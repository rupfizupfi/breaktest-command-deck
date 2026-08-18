package ch.rupfizupfi.deck.testrunner;

import ch.rupfizupfi.deck.device.loadcell.LoadCellDevice;
import ch.rupfizupfi.deck.device.loadcell.MeasurementObserver;
import ch.rupfizupfi.deck.device.api.Measurement;
import ch.rupfizupfi.deck.filesystem.CSVStoreService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

/**
 * Writes the load cell stream to CSV and is the force watchdog of a running test: it is the only
 * component that sees the measured force at all, so it is also the only one that can notice the
 * sensor going away.
 * <p>
 * That matters because a dying load cell does not throw here. A USB error ends the dscusb driver's
 * reader thread, after which {@code getNextValues()} returns empty forever and the measurement
 * buffer simply stops being refilled. Without the detectors below the loop would spin in
 * its empty-buffer branch, no limit check would ever run again, and the motor would keep pulling
 * past the shut-off threshold until the sample or the machine fails. Silence is therefore treated as
 * a fault in its own right, next to frozen and nonsensical readings.
 * <p>
 * The trip reason names the driver's own cause where it has one ({@link
 * LoadCellDevice#getStreamFailure()}), but that is a diagnosis only: what escalates is always the
 * silence, so a sensor that dies without explanation still trips on the same timeout.
 */
public class LoadCellThread implements MeasurementObserver {
    private static final Logger logger = LoggerFactory.getLogger(LoadCellThread.class);

    /** No-data timeout. Arms only after the first measurement, so a slow startup does not trip it. */
    private static final long NO_DATA_TIMEOUT_MS = 250;
    /** A live strain gauge always has LSB noise; bit-identical samples mean a frozen/replayed reading. */
    private static final int FROZEN_SAMPLE_LIMIT = 100;
    /** Machine range. Matches the peak-extraction cap used elsewhere; belongs in Setting eventually. */
    private static final float RATED_CAPACITY_NEWTON = 300_000f;
    private static final float IMPLAUSIBLE_FORCE_NEWTON = 1.5f * RATED_CAPACITY_NEWTON;
    /**
     * Largest force step, in newton, that one sample-to-sample interval can plausibly GAIN. The
     * crosshead cannot load half the machine's rated capacity within a single sample period, so a
     * jump this large is a corrupted frame, not mechanics. Deliberately generous: this detector
     * exists to catch garbage, not to second-guess a stiff sample.
     */
    private static final float IMPLAUSIBLE_RISE_NEWTON = RATED_CAPACITY_NEWTON / 2f;
    /** M-of-N vote so a single glitch sample does not abort a run. */
    private static final int PLAUSIBILITY_VOTE_REQUIRED = 3;
    private static final int PLAUSIBILITY_VOTE_WINDOW = 5;

    private volatile boolean running = false;
    private final TestContext testContext;
    private volatile float minValue;
    private volatile float maxValue;
    private final CSVStoreService csvStoreService;
    private final LoadCellDevice loadCellDevice;
    private final MotorSafetyController motorSafety;
    private final List<Measurement> measurementBuffer = new CopyOnWriteArrayList<>();
    private final Object lock = new Object();
    private Thread thread;

    /**
     * Last force the watchdog saw, {@link Float#NaN} until the first sample. Volatile because the
     * incident log and the UI read it from other threads while the loop keeps writing it.
     */
    private volatile float lastForce = Float.NaN;

    // Watchdog state below is touched only by the measurement loop (run()), so it needs no
    // synchronization; update() runs on the driver's reader thread and only fills the buffer.

    /** nanoTime of the last non-empty drain. nanoTime, never currentTimeMillis: a watchdog must not
     * be shortened or stretched by an NTP step or a manual clock change on the bench machine. */
    private long lastDataNanos;
    /** Arms the no-data detector. Before the first measurement there is nothing to have gone silent. */
    private boolean dataSeen = false;

    private int previousForceBits;
    private boolean hasPreviousForce = false;
    private int frozenSampleCount = 0;

    /** Ring buffer of the last {@link #PLAUSIBILITY_VOTE_WINDOW} verdicts, true = implausible. */
    private final boolean[] plausibilityVotes = new boolean[PLAUSIBILITY_VOTE_WINDOW];
    private int plausibilityVoteIndex = 0;
    private int implausibleVotes = 0;

    /** Set by the first trip, so one incident can never escalate twice. */
    private boolean sensorLostTripped = false;

    LoadCellThread(TestContext testContext, LoadCellDevice loadCellDevice, CSVStoreService csvStoreService, MotorSafetyController motorSafety) {
        this.testContext = testContext;
        this.loadCellDevice = loadCellDevice;
        this.csvStoreService = csvStoreService;
        this.motorSafety = motorSafety;
        minValue = (float) testContext.getLowerLimit();
        maxValue = (float) testContext.getUpperLimit();
    }

    public void start() {
        setRunning(true);
        thread = new Thread(this::run);
        thread.start();
    }

    public void stop() {
        setRunning(false);
        Thread current = thread;
        if (current == null) {
            return;
        }
        current.interrupt();
        try {
            current.join(100);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public void setRunning(boolean running) {
        this.running = running;
    }

    public float getMaxValue() {
        return maxValue;
    }

    public float getMinValue() {
        return minValue;
    }

    public void setMaxValue(float maxValue) {
        this.maxValue = maxValue;
    }

    public void setMinValue(float minValue) {
        this.minValue = minValue;
    }

    /** Last force the watchdog saw, Float.NaN if none yet. */
    public float getLastForce() {
        return lastForce;
    }

    @Override
    public void update(List<Measurement> measurements) {
        synchronized (lock) {
            measurementBuffer.addAll(measurements);
        }
    }

    protected void run() {
        String filePath = csvStoreService.generateFilePathForTestResult(testContext.getTestResultId());
        boolean connected = false;
        // True only once the measurement loop has ended on its own stop condition. Anything that
        // fails afterwards (notably the implicit writer.close() of the try-with-resources, which
        // flushes and can hit a full disk) is a data problem, not a safety problem: the runner has
        // already cleared `running` and is shutting the motor down itself.
        boolean loopStoppedNormally = false;

        try (BufferedWriter writer = new BufferedWriter(new FileWriter(filePath))) {
            loadCellDevice.connect();
            connected = true;
            loadCellDevice.registerObserver(this);

            while (running) {
                if (measurementBuffer.isEmpty()) {
                    // The structurally important detector, and the reason it lives here: this is the
                    // ONLY branch that still executes once the sensor is dead. Everything below runs
                    // on data that a dead sensor no longer delivers.
                    // `running` is re-read immediately before escalating so a normal teardown - the
                    // runner disconnects the device and clears the flag from another thread - cannot
                    // be mistaken for a silent sensor.
                    if (noDataTimedOut() && running) {
                        sensorLost(describeSilence());
                        break;
                    }

                    Thread.sleep(20);
                    continue;
                }

                List<Measurement> measurements;
                synchronized (lock) {
                    measurements = new ArrayList<>(measurementBuffer);
                    measurementBuffer.clear();
                }

                // Reaching here means the buffer held data, which is the only proof the sensor is
                // alive. Recorded before the batch is processed so a slow disk cannot make the
                // watchdog think the hardware was late.
                lastDataNanos = System.nanoTime();
                dataSeen = true;

                // First fault of the batch wins, but the whole batch is still written: the samples
                // leading up to a sensor loss are the most interesting ones in the incident file.
                String fault = null;
                for (Measurement measurement : measurements) {
                    minValue = Math.min(minValue, measurement.force());
                    maxValue = Math.max(maxValue, measurement.force());

                    writer.write(measurement.timestamp() + "," + measurement.force());
                    writer.newLine();

                    // Per sample, never per batch: one drain can carry 50 measurements, and a frozen
                    // counter or a 3-of-5 vote advanced once per drain would need 50x longer to trip.
                    String sampleFault = inspectSample(measurement.force());
                    if (fault == null) {
                        fault = sampleFault;
                    }
                }

                if (fault != null && running) {
                    sensorLost(fault);
                    break;
                }

                Measurement lastMeasurement = measurements.getLast();
                if (lastMeasurement.force() > testContext.getUpperLimit()) {
                    testContext.sendSignal(TestContext.RELEASE_SIGNAL);
                } else if (lastMeasurement.force() < testContext.getLowerLimit()) {
                    testContext.sendSignal(TestContext.PULL_SIGNAL);
                }
            }
            // Deliberately the first statement after the loop: an exception thrown from inside the
            // loop body must skip this so the catch below still escalates. A watchdog trip does
            // reach it, on purpose - it has already stopped the motor itself, so a failing
            // writer.close() afterwards must not trigger a second escalation.
            loopStoppedNormally = true;
        } catch (InterruptedException e) {
            // The normal stop() path; not a fault, so the motor is left to the runner's own shutdown.
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            logger.error("Load cell thread failed", e);

            if (loopStoppedNormally) {
                // The force was watched for the whole time the motor could be running; only the
                // writer teardown failed. Escalating here would emergency-stop a motor the runner
                // is already stopping, so we just flag the CSV as untrustworthy.
                logger.error("Load cell measurement data may be incomplete in {}", filePath);
            } else {
                // Without this loop nobody is watching the force limits, so the motor would keep
                // pulling blind. De-energize first, tell the runner afterwards.
                SafeStopResult result = motorSafety.safeStop("load cell thread failed: " + e.getMessage());
                if (result.coasting()) {
                    // The stop itself worked; only the force watchdog is gone, so warn without
                    // claiming the motor stop failed.
                    logger.warn("Load cell thread failed; motor de-energized and coasting down at {} rpm", result.motorSpeedRpm());
                } else if (result.needsOperatorAttention()) {
                    logger.error("Load cell thread failed AND motor stop could not be verified: {}", result.detail());
                }

                try {
                    testContext.sendSignal(0);
                } catch (Exception signalFailure) {
                    logger.error("Could not signal the test runner after a load cell thread failure", signalFailure);
                }
            }
        } finally {
            try {
                loadCellDevice.unregisterObserver(this);
            } catch (Exception e) {
                logger.error("Could not unregister the load cell observer", e);
            }

            if (connected) {
                try {
                    loadCellDevice.disconnect();
                } catch (Exception e) {
                    logger.error("Could not disconnect the load cell device", e);
                }
            }

            running = false;
        }
    }

    /**
     * True when the stream has gone silent for longer than {@link #NO_DATA_TIMEOUT_MS}. Disarmed
     * until the first measurement of the run has been seen: connecting the device, spawning the
     * driver's reader thread and getting the first frame out of the USB stack can easily take longer
     * than the timeout, and a slow start is not a dead sensor.
     */
    private boolean noDataTimedOut() {
        if (!dataSeen) {
            return false;
        }

        return System.nanoTime() - lastDataNanos > TimeUnit.MILLISECONDS.toNanos(NO_DATA_TIMEOUT_MS);
    }

    /**
     * The no-data trip reason, naming the driver's account of why its reader stopped when there is
     * one. Never throws: a diagnostic must not be able to stop the escalation it describes, so any
     * failure degrades to the plain timeout message. Throwable, not Exception, because a linkage
     * error from a mismatched dscusb jar is exactly the case that would take the watchdog down.
     */
    private String describeSilence() {
        String reason = "no measurement for more than " + NO_DATA_TIMEOUT_MS + " ms";
        try {
            String cause = loadCellDevice.getStreamFailure();
            if (cause != null) {
                return reason + " (" + cause + ")";
            }
        } catch (Throwable t) {
            logger.warn("Could not determine why the load cell stream went silent", t);
        }

        return reason;
    }

    /**
     * Runs the frozen-value and plausibility detectors over one sample and advances their state.
     *
     * @return the trip reason, or null when the sample is acceptable
     */
    private String inspectSample(float force) {
        String reason = null;

        // Raw bits, not ==, for two reasons: == says 0.0f equals -0.0f (two distinct readings the
        // hardware can genuinely produce) and says NaN never equals NaN (so a stuck NaN would never
        // be recognised as stuck). Bit equality compares exactly what the driver delivered. And it
        // is a valid test at all only because a live strain gauge always dithers in its low bits:
        // 100 bit-identical samples in a row means the value is being replayed, not measured.
        int bits = Float.floatToRawIntBits(force);
        if (hasPreviousForce && bits == previousForceBits) {
            frozenSampleCount++;
            if (frozenSampleCount >= FROZEN_SAMPLE_LIMIT) {
                reason = "force frozen at " + force + " N for " + frozenSampleCount
                        + " consecutive bit-identical samples";
            }
        } else {
            frozenSampleCount = 0;
        }

        boolean implausible = isImplausible(force);
        // Sliding window of the last PLAUSIBILITY_VOTE_WINDOW verdicts: drop the verdict that ages
        // out of the window, add the new one, then count.
        if (plausibilityVotes[plausibilityVoteIndex]) {
            implausibleVotes--;
        }
        plausibilityVotes[plausibilityVoteIndex] = implausible;
        if (implausible) {
            implausibleVotes++;
        }
        plausibilityVoteIndex = (plausibilityVoteIndex + 1) % PLAUSIBILITY_VOTE_WINDOW;

        if (reason == null && implausibleVotes >= PLAUSIBILITY_VOTE_REQUIRED) {
            reason = "implausible force readings, " + implausibleVotes + " of the last "
                    + PLAUSIBILITY_VOTE_WINDOW + " samples were rejected, last value " + force + " N";
        }

        previousForceBits = bits;
        hasPreviousForce = true;
        lastForce = force;
        return reason;
    }

    /**
     * One sample's plausibility verdict. Deliberately only a vote: a single corrupted frame is
     * common on a USB line and must not abort a run on its own.
     */
    private boolean isImplausible(float force) {
        if (Float.isNaN(force) || Float.isInfinite(force)) {
            return true;
        }

        if (Math.abs(force) > IMPLAUSIBLE_FORCE_NEWTON) {
            return true;
        }

        float previous = lastForce;
        if (Float.isNaN(previous)) {
            // First sample of the run: nothing to compare a step against.
            return false;
        }

        // ONLY RISES VOTE, AND THIS MUST STAY THAT WAY. A fast force DROP is exactly what a sample
        // breaking looks like - the single most important event this machine measures - so treating
        // a drop as "implausible" would suppress the real reading and let the run continue on a
        // rejected measurement. Whoever "simplifies" this into Math.abs(force - previous) breaks
        // the machine's core purpose.
        // The step is measured on the magnitude so that loading in either direction is covered;
        // a fall in magnitude, i.e. a release toward zero, can never produce a positive rise.
        float rise = Math.abs(force) - Math.abs(previous);
        return rise > IMPLAUSIBLE_RISE_NEWTON;
    }

    /**
     * Single escalation path for all three detectors. Runs synchronously on the measurement thread
     * on purpose: handing this to the signal queue would be useless precisely when it is needed,
     * because the runner thread may be blocked in {@code processSignals()} - which is the situation
     * the watchdog exists for.
     */
    private void sensorLost(String reason) {
        if (sensorLostTripped) {
            // The callers break out of the loop on the first trip; this is the backstop that
            // guarantees one incident can never produce two escalations.
            return;
        }
        sensorLostTripped = true;

        logger.error("Load cell watchdog tripped: {}. Last known force: {} N", reason, lastForce);

        // De-energize FIRST. Nobody is watching the force any more, so every millisecond spent
        // notifying anyone is a millisecond the motor keeps pulling blind. A stop that the runner's
        // own cleanup() repeats later is harmless: MotorSafetyController latches the stop and
        // replays an escalation it already performed instead of re-running it against the hardware.
        SafeStopResult result = motorSafety.safeStop("load cell lost: " + reason);
        if (result.coasting()) {
            // The stop itself worked; only the force watchdog is gone, so warn without
            // claiming the motor stop failed.
            logger.warn("Load cell lost; motor de-energized and coasting down at {} rpm", result.motorSpeedRpm());
        } else if (result.needsOperatorAttention()) {
            logger.error("Load cell lost AND motor stop could not be verified: {}", result.detail());
        }

        try {
            testContext.sendSignal(0);
        } catch (Exception signalFailure) {
            logger.error("Could not signal the test runner after a load cell loss", signalFailure);
        }

        // Ends the measurement loop even if a caller ever forgets to break, so the finally block
        // runs exactly once and no further sample can trip anything.
        running = false;
    }
}
