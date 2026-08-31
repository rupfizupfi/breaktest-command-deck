package ch.rupfizupfi.deck.testrunner;

import ch.rupfizupfi.deck.device.loadcell.LoadCellDevice;
import ch.rupfizupfi.deck.device.loadcell.MeasurementObserver;
import ch.rupfizupfi.deck.device.api.Measurement;
import ch.rupfizupfi.deck.filesystem.CSVStoreService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

/**
 * Writes the load cell stream to CSV and is the force watchdog of a running test: the only
 * component that sees the measured force, so the only one that can notice the sensor going away.
 * <p>
 * The three detectors, their thresholds and what escalation each feeds are owned by the detection
 * section of {@code doc/06-feature-work/testrunner-safety/loadcell-recovery-design.md}.
 * <p>
 * What that doc cannot say, and every edit here has to respect: a dying load cell does not throw.
 * A USB error ends the driver's reader thread, {@code getNextValues()} returns empty forever, and
 * without the detectors this loop would spin in its empty-buffer branch while the motor kept
 * pulling past the shut-off threshold. Silence is a fault in its own right.
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
     * Largest force step, in newton, one sample interval can plausibly GAIN. Deliberately generous:
     * this catches corrupted frames, it does not second-guess a stiff sample.
     */
    private static final float IMPLAUSIBLE_RISE_NEWTON = RATED_CAPACITY_NEWTON / 2f;
    /** M-of-N vote so a single glitch sample does not abort a run. */
    private static final int PLAUSIBILITY_VOTE_REQUIRED = 3;
    private static final int PLAUSIBILITY_VOTE_WINDOW = 5;
    /**
     * Bit-identical samples in a row that fail the recovery gate. Far tighter than
     * {@link #FROZEN_SAMPLE_LIMIT}: the gate window is milliseconds, so a run-of-100 could never
     * complete inside it and reusing that limit would be dead code. A first estimate, to be retuned
     * against a real specimen alongside the drift fraction.
     */
    private static final int GATE_FROZEN_RUN = 10;
    /** Grace on top of the gate window before a gate that never gets clean samples gives up. */
    private static final long GATE_DEADLINE_SLACK_MS = 1000;
    /** Park interval of the hold loop; same cadence as the empty-buffer branch. */
    private static final long PARK_INTERVAL_MS = 20;

    private volatile boolean running = false;
    private final TestContext testContext;
    private volatile float minValue;
    private volatile float maxValue;
    private final String filePath;
    private final LoadCellDevice loadCellDevice;
    private final MotorSafetyController motorSafety;
    private final SensorLossListener sensorLossListener;
    private final RecoveryProperties recovery;
    private final GapRecorder gapRecorder;
    private final List<Measurement> measurementBuffer = new CopyOnWriteArrayList<>();
    private final Object lock = new Object();
    private Thread thread;

    /**
     * One writer for the whole run, opened in {@link #run()} and closed in its finally. It stays open
     * across a hold on purpose: a resumed run then has ONE continuous CSV, byte-identical in format
     * to a run that never lost the sensor, with the gap visible only as a jump in the timestamp
     * column. Touched by the measurement thread ALONE - a close or a write from the reconnector
     * would interleave with or truncate the file.
     */
    private BufferedWriter writer;

    /** Parked: the sensor is gone and the motor is already stopped, so there is nothing to watch. */
    private volatile boolean holding = false;
    /** Draining and writing a reconnected stream, but not yet trusted to move the motor. */
    private volatile boolean gating = false;

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

    /**
     * Raised by {@link #markSensorRecovered()} on the reconnector thread and consumed by the
     * measurement loop, which is what keeps the invariant above true: the watchdog state stays the
     * loop's alone and nothing else ever writes it.
     */
    private volatile boolean recoveryRequested = false;

    /**
     * False from the moment a recovery is requested until the loop has actually applied the reset.
     * In that window {@link #dataSeen} and {@link #lastDataNanos} still describe the dead session, so
     * the no-data detector would trip on a stream that has only just been handed back to it.
     */
    private volatile boolean watchdogArmed = true;

    /** Completed by the measurement thread; null whenever no gate is in flight. */
    private volatile CompletableFuture<GateResult> gateFuture;

    // Gate state below is published to the measurement thread by the volatile `gating` write at the
    // end of beginRecoveryGate, and read only while gating is true.
    private float gateBaseline;
    private double gateEnvelope;
    private long gateDeadlineNanos;
    /** nanoTime the current unbroken run of acceptable samples started; 0 while there is no run. */
    private long gateRunStartNanos;
    /** First sample seen after the gate opened, NaN until one arrives. Drift is measured from it. */
    private float gateFirstForce = Float.NaN;
    private int gatePreviousBits;
    private boolean gateHasPreviousBits = false;
    private int gateFrozenRun = 0;
    private String gateLastFailure;

    LoadCellThread(TestContext testContext, LoadCellDevice loadCellDevice,
                   CSVStoreService.TestRunFiles runFiles, MotorSafetyController motorSafety,
                   SensorLossListener sensorLossListener, RecoveryProperties recovery,
                   GapRecorder gapRecorder) {
        this.testContext = testContext;
        this.loadCellDevice = loadCellDevice;
        this.filePath = runFiles.forceCsvPath();
        this.motorSafety = motorSafety;
        this.sensorLossListener = sensorLossListener;
        this.recovery = recovery;
        this.gapRecorder = gapRecorder;
        minValue = (float) testContext.getLowerLimit();
        maxValue = (float) testContext.getUpperLimit();
    }

    /** What one recovery gate concluded, and the drift numbers it concluded it from. */
    public record GateResult(boolean passed, float firstForce, float drift, double driftFraction,
                             String detail) {
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

    /**
     * The measurement loop's thread, null before {@link #start()}. Package-private for tests, same
     * widening as {@link #inspectSample}: {@link #stop()} joins only 100 ms, so a teardown that must
     * prove the loop actually died needs the thread itself to join on.
     */
    Thread loopThread() {
        return thread;
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
        boolean connected = false;
        // Tells the catch below "the loop threw while the motor could still be running" from "the
        // loop reached its own stop condition and something after it failed". Only the first may
        // emergency-stop: in the second the runner has already cleared `running` and is shutting the
        // motor down itself, so escalating would fight it over a motor it is already stopping.
        boolean loopStoppedNormally = false;

        try {
            writer = new BufferedWriter(new FileWriter(filePath));
            loadCellDevice.connect();
            connected = true;
            loadCellDevice.registerObserver(this);

            while (running) {
                // Applied here rather than by the caller: see markSensorRecovered().
                if (recoveryRequested) {
                    applyRecoveryReset();
                }

                if (holding) {
                    // `running` still exits the park, so an abort is not delayed by the hold.
                    // The buffer is dropped rather than kept: these are post-loss samples no
                    // detector has vouched for, and a whole hold's worth from a sensor that is still
                    // pushing frames would grow a copy-on-write list by one full copy per batch.
                    synchronized (lock) {
                        measurementBuffer.clear();
                    }
                    Thread.sleep(PARK_INTERVAL_MS);
                    continue;
                }

                if (gating) {
                    checkGateDeadline();
                }

                if (measurementBuffer.isEmpty()) {
                    // This is the ONLY branch that still executes once the sensor is dead, which is
                    // why the no-data detector lives here and not below. `running` is re-read right
                    // before escalating so a normal teardown - the runner clearing the flag from
                    // another thread - is not mistaken for a silent sensor.
                    if (noDataTimedOut() && running) {
                        // No break: sensorLost() parks the loop instead of ending it, so the writer
                        // stays open and a reconnect can resume into the same file.
                        sensorLost(describeSilence());
                        continue;
                    }

                    Thread.sleep(PARK_INTERVAL_MS);
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
                    writeSample(measurement);

                    // Per sample, never per batch: one drain can carry 50 measurements, and a frozen
                    // counter or a 3-of-5 vote advanced once per drain would need 50x longer to trip.
                    // Also where the measured envelope is updated, because that may only happen for a
                    // sample that has already passed its own plausibility check.
                    String sampleFault = inspectSample(measurement.force());
                    if (fault == null) {
                        fault = sampleFault;
                    }

                    if (gating) {
                        scoreGateSample(measurement.force());
                    }
                }

                if (fault != null && running) {
                    sensorLost(fault);
                    continue;
                }

                // No limit signal while a gate is open: the samples are written and scored, but a
                // stream that has not yet proven itself must not be allowed to move the motor.
                if (gating) {
                    continue;
                }

                // Never decided from a non-finite sample. Both comparisons are false for NaN, so an
                // unguarded check reads as "inside both limits" and silently skips a shut-off. The
                // sample has already been counted toward the plausibility vote; the limit decision
                // simply waits for the next batch, which is at most one 20 ms drain away.
                float latest = measurements.getLast().force();
                if (Float.isFinite(latest)) {
                    if (latest > testContext.getUpperLimit()) {
                        testContext.sendSignal(TestContext.RELEASE_SIGNAL);
                    } else if (latest < testContext.getLowerLimit()) {
                        testContext.sendSignal(TestContext.PULL_SIGNAL);
                    }
                }
            }
            // Deliberately the first statement after the loop: an exception thrown from inside the
            // loop body must skip this so the catch below still escalates. A watchdog trip parks the
            // loop rather than ending it, so the only way here is `running` going false.
            loopStoppedNormally = true;
        } catch (InterruptedException e) {
            // The normal stop() path; not a fault, so the motor is left to the runner's own shutdown.
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            logger.error("Load cell thread failed", e);

            if (loopStoppedNormally) {
                // The force was watched for the whole time the motor could be running, so this is a
                // data problem, not a safety one; flag the CSV as untrustworthy and leave the motor
                // to the runner's own shutdown.
                logger.error("Load cell measurement data may be incomplete in {}", filePath);
            } else {
                // Without this loop nobody is watching the force limits, so the motor would keep
                // pulling blind. De-energize first, tell the runner afterwards.
                SafeStopResult result = motorSafety.safeStop("load cell thread failed: " + e.getMessage());
                if (result.coasting()) {
                    // The stop worked, only the watchdog is gone: warn, do not claim a failed stop.
                    logger.warn("Load cell thread failed; motor de-energized and coasting down at {} rpm", result.motorSpeedRpm());
                } else if (result.needsOperatorAttention()) {
                    logger.error("Load cell thread failed AND motor stop could not be verified: {}", result.detail());
                }

                // Not sendSignal(0): a bare stop signal is indistinguishable from a limit crossing,
                // so the runner would record a run that died on a writer failure as one that finished
                // normally. The listener ends it as a FAULT and unblocks the runner itself.
                try {
                    sensorLossListener.onWatchdogFailure("load cell thread failed: " + e.getMessage());
                } catch (Exception signalFailure) {
                    logger.error("Could not signal the test runner after a load cell thread failure", signalFailure);
                    testContext.sendSignal(0);
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

            // Closed only here, after the observer is gone and the device is down, so nothing can
            // still be handing this thread a batch to write.
            BufferedWriter openWriter = writer;
            writer = null;
            if (openWriter != null) {
                try {
                    openWriter.close();
                } catch (Exception e) {
                    logger.error("Could not close the load cell CSV {}", filePath, e);
                }
            }

            // A reconnector waiting on a gate must never be left parked on a thread that has ended.
            completeGate(false, "the measurement thread ended before the gate could finish");

            running = false;
        }
    }

    /**
     * True when the stream has gone silent for longer than {@link #NO_DATA_TIMEOUT_MS}. Disarmed
     * until the first measurement: connect + reader spawn + first USB frame easily outlast the
     * timeout, and a slow start is not a dead sensor. A reconnect is the same slow start, hence
     * {@link #watchdogArmed}.
     */
    private boolean noDataTimedOut() {
        if (!watchdogArmed || !dataSeen) {
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
     * Runs both per-sample detectors and folds the sample into the measured envelope. The envelope
     * update lives here because it may only happen on the far side of the plausibility check, and
     * this is the only place that verdict exists.
     *
     * @return the trip reason, or null when the sample is acceptable
     */
    // Package-private: production code must only call this from the measurement loop.
    String inspectSample(float force) {
        String reason = null;

        // Raw bits, not ==: == says 0.0f equals -0.0f (two readings the hardware genuinely produces)
        // and that NaN never equals NaN (so a stuck NaN would never read as stuck).
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

        // The envelope may only grow from a sample that passed the check above: Math.min/Math.max are
        // NaN-absorbing, and one NaN - a single vote, two short of a trip - would pin both bounds at
        // NaN, retiring the shut-off silently with the motor still driving. Full failure trace in
        // doc/06-feature-work/testrunner-safety/loadcell-recovery-design.md.
        if (!implausible) {
            minValue = Math.min(minValue, force);
            maxValue = Math.max(maxValue, force);
        }

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

        // ONLY RISES VOTE, AND THIS MUST STAY THAT WAY. A fast DROP is what a specimen breaking
        // looks like - the most important event this machine measures - so rejecting it would
        // suppress the real reading. Do not "simplify" this into Math.abs(force - previous).
        // Magnitude, so loading in either direction counts and a release toward zero cannot.
        float rise = Math.abs(force) - Math.abs(previous);
        return rise > IMPLAUSIBLE_RISE_NEWTON;
    }

    /**
     * Single escalation path for all three detectors. Synchronous on the measurement thread on
     * purpose: the signal queue would be useless precisely when this is needed, because the runner
     * thread may be blocked in {@code processSignals()}.
     * <p>
     * The STEP ORDER BELOW IS THE SAFETY INVARIANT and is not free to rearrange - each step says why
     * it sits where it does.
     */
    private void sensorLost(String reason) {
        if (sensorLostTripped) {
            // Backstop; callers already stop feeding samples in on the first trip.
            return;
        }
        sensorLostTripped = true;
        float lastKnownForce = lastForce;

        // The drop count rides along: a trip preceded by a rising count is a cell that had been
        // failing frames for a while, one at zero is a cell that was fine until it was not.
        logger.error("Load cell watchdog tripped: {}. Last known force: {} N, driver discarded {} reading(s)",
                reason, lastKnownForce, loadCellDevice.getDroppedSampleCount());

        // 1. De-energize FIRST: nobody is watching the force, so every millisecond spent notifying
        // anyone is a millisecond the motor pulls blind. A repeat from the runner's own cleanup()
        // is harmless - the controller replays a recorded escalation rather than re-running it.
        SafeStopResult result = motorSafety.safeStop("load cell lost: " + reason);
        if (result.coasting()) {
            // The stop worked, only the watchdog is gone: warn, do not claim a failed stop.
            logger.warn("Load cell lost; motor de-energized and coasting down at {} rpm", result.motorSpeedRpm());
        } else if (result.needsOperatorAttention()) {
            logger.error("Load cell lost AND motor stop could not be verified: {}", result.detail());
        }

        try {
            // 2. Whatever the driver still had buffered goes to the file, detectors NOT run: these
            // are pre-loss samples, the most interesting rows in the incident file, and they must
            // not be able to trip anything a second time on the way in.
            drainPendingSamples();
            // 3. Mandatory. Without it the tail of the incident sits in an 8 KB buffer, and a crash
            // during the hold loses exactly the rows the post-mortem is about.
            writer.flush();
        } catch (Exception e) {
            logger.error("Could not persist the samples leading up to the load cell loss in {}", filePath, e);
        }

        // 4. Durable before anything below can fail, so an aborted process still leaves a record
        // that this run was holding rather than finishing.
        gapRecorder.beginGap(reason, lastKnownForce);

        // 5. Synchronously on this thread - see SensorLossListener for why it is not queued.
        try {
            sensorLossListener.onSensorLoss(reason, lastKnownForce);
        } catch (Exception e) {
            logger.error("Sensor loss listener failed after a load cell loss", e);
        }

        // 6. Parked, NOT stopped. `running` deliberately stays true: it is what keeps the CSV open
        // and this thread alive so a reconnect can resume into the same file.
        //
        // The escalation deliberately does NOT go through the signal queue. Signal 0 is
        // indistinguishable from a limit trip at the receiving end, which is what made
        // DestructiveTest treat a lost sensor as a finished test; the abort path sends it
        // deliberately instead.
        holding = true;

        // A gate in flight has just lost its stream; failing it here beats leaving the reconnector
        // waiting for a deadline that nothing will now satisfy.
        completeGate(false, "the sensor was lost again during the gate: " + reason);
    }

    /**
     * Writes the samples the driver has already handed over, without inspecting them. Used on the
     * loss path only; the normal path writes and inspects in one pass.
     */
    private void drainPendingSamples() throws IOException {
        List<Measurement> pending;
        synchronized (lock) {
            pending = new ArrayList<>(measurementBuffer);
            measurementBuffer.clear();
        }

        for (Measurement measurement : pending) {
            writeSample(measurement);
        }
    }

    /** The one place the CSV row format is written, so hold and gate rows are identical to normal ones. */
    private void writeSample(Measurement measurement) throws IOException {
        writer.write(measurement.timestamp() + "," + measurement.force());
        writer.newLine();
    }

    /**
     * Announces that the driver has handed back a live stream. Called from the RECONNECTOR thread,
     * which is why it only raises a flag: the watchdog state it has to clear is the measurement
     * loop's alone (see the invariant at the field declarations), and resetting it from here would
     * be exactly the cross-thread write that invariant rules out. The loop applies it in
     * {@link #applyRecoveryReset()} at the top of its next iteration, park included.
     */
    public void markSensorRecovered() {
        watchdogArmed = false;
        recoveryRequested = true;
    }

    /** Runs on the measurement thread only. */
    private void applyRecoveryReset() {
        recoveryRequested = false;

        // Disarms the no-data detector until the first post-reconnect sample - a reconnect is the
        // same slow start as a boot; see noDataTimedOut().
        dataSeen = false;
        lastDataNanos = 0;

        frozenSampleCount = 0;
        hasPreviousForce = false;
        Arrays.fill(plausibilityVotes, false);
        plausibilityVoteIndex = 0;
        implausibleVotes = 0;
        sensorLostTripped = false;

        // Re-seeded from the configured limits, never carried across the gap: the cyclic tests derive
        // their next force limit from this envelope, and a value measured before the loss describes a
        // specimen on the other side of a hold it has been creeping through.
        minValue = (float) testContext.getLowerLimit();
        maxValue = (float) testContext.getUpperLimit();

        // Anything queued from the dead session is not evidence about the new one and must not be
        // scored by the gate.
        synchronized (lock) {
            measurementBuffer.clear();
        }

        watchdogArmed = true;
    }

    /**
     * Opens the recovery gate: samples flow into the one continuous CSV again, but no limit signal is
     * emitted until {@code plausibilityGateMillis} of unbroken acceptable samples have gone by.
     * <p>
     * Evaluated here rather than by a temporary observer on purpose. It reuses the detectors that
     * already exist, the gate's samples land in the CSV where they belong instead of in a scratch
     * buffer, and there is no second consumer racing {@link #measurementBuffer} for them.
     * <p>
     * THE GATE NEVER TARES. Zeroing the reading is the obvious way to make a failing drift check
     * pass, and it would discard the real force the specimen is under - every later limit decision,
     * and every force in the result file, would then be measured against a fiction.
     *
     * @param envelopeNewton the force envelope the drift gate is a fraction of; the caller has
     *                       already floored it at {@code minEnvelopeNewton}
     */
    public CompletableFuture<GateResult> beginRecoveryGate(float lastKnownForce, double envelopeNewton) {
        CompletableFuture<GateResult> future = new CompletableFuture<>();

        gateBaseline = lastKnownForce;
        gateEnvelope = envelopeNewton;
        gateDeadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(
                2 * recovery.getPlausibilityGateMillis() + GATE_DEADLINE_SLACK_MS);
        gateRunStartNanos = 0;
        gateFirstForce = Float.NaN;
        gateHasPreviousBits = false;
        gateFrozenRun = 0;
        gateLastFailure = null;
        gateFuture = future;

        // Written last, and in this order: the volatile `gating` publishes the plain gate fields
        // above to the measurement thread, and clearing `holding` is what lets it read them.
        gating = true;
        holding = false;
        return future;
    }

    /**
     * Leaves hold and gate mode, so limit signals resume. The watchdog is already live by this point,
     * deliberately: it must be armed BEFORE the caller re-enables the drive, never after.
     */
    public void endHold() {
        gating = false;
        holding = false;
    }

    public boolean isHolding() {
        return holding;
    }

    public int gapCount() {
        return gapRecorder.gapCount();
    }

    /**
     * One sample's verdict inside the gate. Any failing criterion resets the continuity timer, so the
     * gate measures an unbroken run of good samples rather than a good average.
     */
    // Package-private: production code must only call this from the measurement loop.
    void scoreGateSample(float force) {
        if (gateFuture == null) {
            return;
        }

        if (Float.isNaN(gateFirstForce)) {
            gateFirstForce = force;
        }

        int bits = Float.floatToRawIntBits(force);
        gateFrozenRun = gateHasPreviousBits && bits == gatePreviousBits ? gateFrozenRun + 1 : 0;
        gatePreviousBits = bits;
        gateHasPreviousBits = true;

        String failure = null;
        if (!Float.isFinite(force)) {
            failure = "non-finite reading " + force;
        } else if (Math.abs(force) > IMPLAUSIBLE_FORCE_NEWTON) {
            failure = "reading " + force + " N is outside the machine range";
        } else if (gateFrozenRun >= GATE_FROZEN_RUN) {
            failure = "force frozen at " + force + " N for " + (gateFrozenRun + 1)
                    + " consecutive bit-identical samples";
        } else {
            float drift = Math.abs(force - gateBaseline);
            if (!(drift < recovery.getDriftFraction() * gateEnvelope)) {
                failure = "drifted " + drift + " N from the last known " + gateBaseline
                        + " N, more than " + recovery.getDriftFraction() + " of the "
                        + gateEnvelope + " N envelope";
            }
        }

        if (failure != null) {
            gateLastFailure = failure;
            gateRunStartNanos = 0;
            return;
        }

        long now = System.nanoTime();
        if (gateRunStartNanos == 0) {
            gateRunStartNanos = now;
        }
        if (now - gateRunStartNanos >= TimeUnit.MILLISECONDS.toNanos(recovery.getPlausibilityGateMillis())) {
            completeGate(true, "the reconnected stream stayed plausible for "
                    + recovery.getPlausibilityGateMillis() + " ms");
        }
    }

    /** Fails a gate that never gathered an unbroken run, including one that got no samples at all. */
    private void checkGateDeadline() {
        if (gateFuture == null || System.nanoTime() - gateDeadlineNanos < 0) {
            return;
        }

        String why = gateLastFailure != null ? gateLastFailure
                : Float.isNaN(gateFirstForce) ? "no measurement arrived after the reconnect"
                : "the stream never held a plausible run long enough";
        completeGate(false, why);
    }

    /**
     * Drift is always reported, pass or fail: it is the only measurement that will ever exist for
     * retuning the drift fraction against a real specimen, and a rejected recovery is exactly the
     * case worth retuning from.
     */
    private void completeGate(boolean passed, String detail) {
        CompletableFuture<GateResult> future = gateFuture;
        gateFuture = null;
        if (future == null) {
            return;
        }

        float drift = Float.isNaN(gateFirstForce) ? Float.NaN : Math.abs(gateFirstForce - gateBaseline);
        double fraction = gateEnvelope > 0 ? drift / gateEnvelope : Double.NaN;
        logger.info("Load cell recovery gate {}: {} (first force {} N, drift {} N = {} of the envelope)",
                passed ? "passed" : "failed", detail, gateFirstForce, drift, fraction);
        future.complete(new GateResult(passed, gateFirstForce, drift, fraction, detail));
    }
}
