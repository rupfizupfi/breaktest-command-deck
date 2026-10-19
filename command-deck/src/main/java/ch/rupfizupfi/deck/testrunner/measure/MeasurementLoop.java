package ch.rupfizupfi.deck.testrunner.measure;

import ch.rupfizupfi.deck.testrunner.GapRecorder;
import ch.rupfizupfi.deck.testrunner.RecoveryGates;
import ch.rupfizupfi.deck.testrunner.SafeStopResult;
import ch.rupfizupfi.deck.device.loadcell.LoadCellDevice;
import ch.rupfizupfi.deck.device.loadcell.MeasurementObserver;
import ch.rupfizupfi.deck.device.api.Measurement;
import ch.rupfizupfi.deck.filesystem.CSVStoreService;
import ch.rupfizupfi.deck.rig.RigLimits;
import ch.rupfizupfi.deck.testrunner.run.RunEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.FileWriter;
import java.io.IOException;
import java.io.Writer;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;

/**
 * The force watchdog of a running test, and the source of its force CSV: the only component that
 * sees the measured force, so the only one that can notice the sensor going away. The CSV itself is
 * written by a {@link TraceWriter} on its own thread, so a slow disk never delays a detector (A-07).
 * <p>
 * The four detectors, their thresholds and what escalation each feeds are owned by the detection
 * section of {@code doc/dev/backend/safety-and-recovery.md}.
 * <p>
 * A dying load cell does not throw: a USB error ends the driver's reader thread and
 * {@code getNextValues()} returns empty forever, so silence has to be treated as a fault.
 * <p>
 * Named {@code LoadCellThread} in package {@code testrunner} until OQ-64 step 10. It names nothing
 * above {@code testrunner.measure} but the {@link RunEvent} vocabulary and the run's value types;
 * it stops the motor through {@link EmergencyStop} and reports through {@link RunEventSink}
 * ({@code ArchitectureTest}).
 */
public class MeasurementLoop implements MeasurementObserver {
    private static final Logger logger = LoggerFactory.getLogger(MeasurementLoop.class);

    /** No-data timeout. Arms only after the first measurement, so a slow startup does not trip it. */
    private static final long NO_DATA_TIMEOUT_MS = RecoveryGates.NO_DATA_TIMEOUT_MS;
    /**
     * A live strain gauge always has LSB noise; bit-identical samples mean a frozen/replayed reading.
     * The count catches a fast feed; {@code maxFrozenMillis} catches a slow one.
     */
    private static final int FROZEN_SAMPLE_LIMIT = 100;
    /** M-of-N vote so a single glitch sample does not abort a run. */
    private static final int PLAUSIBILITY_VOTE_REQUIRED = 3;
    private static final int PLAUSIBILITY_VOTE_WINDOW = 5;
    /** M-of-N over drains, so one hole the driver absorbed does not abort a run. */
    private static final int SPARSE_DRAIN_VOTE_REQUIRED = 3;
    private static final int SPARSE_DRAIN_VOTE_WINDOW = 5;
    /** Sparse drains that do not trip are logged at most this often; the run's count covers the rest. */
    private static final long SPARSE_DRAIN_LOG_INTERVAL_MS = 1000;
    /**
     * Bit-identical samples in a row that fail the recovery gate. Tighter than
     * {@link #FROZEN_SAMPLE_LIMIT} because a run of 100 cannot complete inside the gate window.
     * First estimate; retune against a real specimen alongside the drift fraction.
     */
    private static final int GATE_FROZEN_RUN = 10;
    /** Grace on top of the gate window before a gate that never gets clean samples gives up. */
    private static final long GATE_DEADLINE_SLACK_MS = 1000;
    /**
     * Park interval of the hold loop, and the longest an idle poll waits while the no-data detector
     * is disarmed. A batch always wakes the loop at once; this only bounds how late it notices a
     * flag raised from another thread with no data flowing.
     */
    private static final long PARK_INTERVAL_MS = 20;
    /**
     * Batches the reader may queue ahead of the loop. The reader delivers about fifty a second, so
     * this is twenty seconds of a stalled loop. A full queue means every detector is deciding on a
     * force that old, so the overflow is a safety event, not a data loss: the reader de-energizes
     * and ends the run as a {@link RunEvent.WatchdogFailed}, as a dead loop does
     * ({@link #update}). Package-private for the test that fills it.
     */
    static final int QUEUE_CAPACITY_BATCHES = 1024;

    private volatile boolean running = false;
    private final ForceLimits testContext;
    /**
     * The measured envelope. Written only by the measurement loop: a runner-thread write raced its
     * read-modify-write in {@link #inspectSample} and was lost. The runner posts a reset through
     * {@link #pendingMinReset}/{@link #pendingMaxReset} instead, as it does a recovery reset.
     */
    private volatile float minValue;
    private volatile float maxValue;
    /** A posted envelope reset, consumed by the loop. Own object per post, so the CAS is by identity. */
    private record EnvelopeReset(float value) {
    }
    private final AtomicReference<EnvelopeReset> pendingMinReset = new AtomicReference<>();
    private final AtomicReference<EnvelopeReset> pendingMaxReset = new AtomicReference<>();
    private final String filePath;
    private final LoadCellDevice loadCellDevice;
    private final EmergencyStop emergencyStop;
    /** The run loop: crossings, a sensor loss, a ceiling trip and the loop's own death leave through here. */
    private final RunEventSink events;
    private final RecoveryGates gates;
    private final GapRecorder gapRecorder;
    /** 1.5 × the run's rated capacity: a reading past it is a corrupt frame, not a force. */
    private final float implausibleForceNewton;
    /**
     * Largest force gain per sample interval, half the rated capacity. Generous: catches corrupt
     * frames, not stiff samples.
     */
    private final float implausibleRiseNewton;
    /**
     * Batches handed over by {@link #update} on the reader thread; the loop blocks on it with the
     * no-data timeout, so a batch wakes it at once and silence is the poll running out.
     */
    private final BlockingQueue<List<Measurement>> inbound = new LinkedBlockingQueue<>(QUEUE_CAPACITY_BATCHES);
    /** Batches {@link #update} could not queue; the first one trips the overflow stop. */
    private final AtomicLong droppedBatches = new AtomicLong();
    /** The crossing detector; fed only by the measurement loop. */
    private final LimitEdgeDetector edge;
    /**
     * The no-data detector's clock, nanoTime so a wall-clock step cannot skew it. A seam for the
     * test that proves the timeout counts from the batch's arrival, not from the end of its
     * processing; set before {@link #start()}.
     */
    private LongSupplier watchdogClock = System::nanoTime;
    /** The recovery gate's clock, nanoTime like the watchdog's. A seam; set before {@link #start()}. */
    private LongSupplier gateClock = System::nanoTime;
    /**
     * Released by the loop's first sample. The no-data detector arms on that sample, so the motor
     * must not be energized before it: a stream dying earlier would otherwise never trip.
     */
    private final CountDownLatch firstSampleSeen = new CountDownLatch(1);
    private Thread thread;

    /** Opens the CSV's underlying writer; a seam so a test can stall or fail the disk. */
    @FunctionalInterface
    interface TraceOutput {
        Writer open(String path) throws IOException;
    }

    private TraceOutput traceOutput = FileWriter::new;

    /**
     * One trace for the whole run, opened in {@link #run()} and closed in its finally. Stays open
     * across a hold so a resumed run has one continuous CSV, the gap visible only as a jump in the
     * timestamp column. Offered to by the measurement thread only, so rows keep the stream's order.
     */
    private TraceWriter trace;

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
    // synchronization; update() runs on the driver's reader thread and only fills the queue.

    /** {@link #watchdogClock} when the loop last took a batch off the queue, before processing it. */
    private long lastDataNanos;
    /** Arms the no-data detector. */
    private boolean dataSeen = false;

    private int previousForceBits;
    private boolean hasPreviousForce = false;
    private int frozenSampleCount = 0;
    /** Sample time the bit-identical run has lasted: each repeat's share of its drain's interval. */
    private long frozenNanos;

    /** Ring buffer of the last {@link #PLAUSIBILITY_VOTE_WINDOW} verdicts, true = implausible. */
    private final boolean[] plausibilityVotes = new boolean[PLAUSIBILITY_VOTE_WINDOW];
    private int plausibilityVoteIndex = 0;
    private int implausibleVotes = 0;

    /**
     * Ring buffer of the last {@link #SPARSE_DRAIN_VOTE_WINDOW} drains' sample-interval verdicts,
     * true = sparser than {@code maxSampleIntervalMillis} per sample.
     */
    private final boolean[] sparseDrainVotes = new boolean[SPARSE_DRAIN_VOTE_WINDOW];
    private int sparseDrainVoteIndex = 0;
    private int sparseVotes = 0;
    /**
     * Sparse drains over the whole run, across reconnects: the evidence the bench calibration of
     * {@code maxSampleIntervalMillis} reads. Written only by the measurement thread.
     */
    private volatile long sparseDrainCount = 0;
    /** {@link #watchdogClock} when a sparse drain was last logged. */
    private long sparseDrainLoggedNanos;

    /** Set by the first trip, so one incident can never escalate twice. */
    private boolean sensorLostTripped = false;
    /** Set by the first ceiling crossing; the run is over, so it is never reset. */
    private boolean ceilingTripped = false;

    /**
     * Raised by {@link #markSensorRecovered()} on the reconnector thread, consumed by the measurement
     * loop, so the watchdog state above keeps a single writer.
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
    /** {@link #gateClock} when the current unbroken run of acceptable samples started; 0 if none. */
    private long gateRunStartNanos;
    /** Acceptable samples in that run. */
    private int gateRunSamples;
    /** First sample seen after the gate opened, NaN until one arrives. Drift is measured from it. */
    private float gateFirstForce = Float.NaN;
    private int gatePreviousBits;
    private boolean gateHasPreviousBits = false;
    private int gateFrozenRun = 0;
    private String gateLastFailure;

    public MeasurementLoop(ForceLimits testContext, LoadCellDevice loadCellDevice,
                           CSVStoreService.TestRunFiles runFiles, EmergencyStop emergencyStop,
                           RunEventSink events, RecoveryGates gates,
                           RigLimits rigLimits, GapRecorder gapRecorder) {
        this.testContext = testContext;
        this.loadCellDevice = loadCellDevice;
        this.filePath = runFiles.forceCsvPath();
        this.emergencyStop = emergencyStop;
        this.events = events;
        this.gates = gates;
        this.gapRecorder = gapRecorder;
        float ratedCapacityNewton = (float) rigLimits.ratedCapacityNewton();
        this.implausibleForceNewton = 1.5f * ratedCapacityNewton;
        this.implausibleRiseNewton = ratedCapacityNewton / 2f;
        minValue = (float) testContext.getLowerLimit();
        maxValue = (float) testContext.getUpperLimit();
        this.edge = new LimitEdgeDetector(testContext, events);
    }

    /**
     * Re-arms both crossing edges, so the first crossing after a resume is not filtered as a repeat
     * of the last one before the loss. Any thread.
     */
    public void resetEdges() {
        edge.reset();
    }

    /**
     * The gate a timed turn enters the run through: the crossings' repeat filter, so a timed turn
     * re-arms the opposite edge and a repeat of the last turn is dropped. Any thread.
     */
    public RunEventSink timedTurns() {
        return event -> {
            if (!(event instanceof RunEvent.TimedTurn turn) || edge.admit(turn.next())) {
                events.accept(event);
            }
        };
    }

    /** Test seam: replaces the no-data detector's clock. Call before {@link #start()}. */
    void useWatchdogClock(LongSupplier clock) {
        this.watchdogClock = clock;
    }

    /** Test seam: replaces the recovery gate's clock. Call before {@link #start()}. */
    void useGateClock(LongSupplier clock) {
        this.gateClock = clock;
    }

    long sparseDrainCount() {
        return sparseDrainCount;
    }

    /** Test seam: replaces the file the trace writes to. Call before {@link #start()}. */
    void useTraceOutput(TraceOutput output) {
        this.traceOutput = output;
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

    private void setRunning(boolean running) {
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

    /** The envelope maximum; a reset not yet consumed by the loop reads as already applied. */
    public float getMaxValue() {
        EnvelopeReset pending = pendingMaxReset.get();
        return pending != null ? pending.value() : maxValue;
    }

    /** The envelope minimum; a reset not yet consumed by the loop reads as already applied. */
    public float getMinValue() {
        EnvelopeReset pending = pendingMinReset.get();
        return pending != null ? pending.value() : minValue;
    }

    /** Posts a reset of the envelope maximum; the loop applies it before folding its next sample. */
    public void setMaxValue(float maxValue) {
        pendingMaxReset.set(new EnvelopeReset(maxValue));
    }

    /** Posts a reset of the envelope minimum; the loop applies it before folding its next sample. */
    public void setMinValue(float minValue) {
        pendingMinReset.set(new EnvelopeReset(minValue));
    }

    /**
     * Measurement thread only. The field is written before the post is cleared, so a reader never
     * sees neither; a newer post landing in between fails the CAS and is applied on the next sample.
     */
    private void applyPendingEnvelopeResets() {
        EnvelopeReset min = pendingMinReset.get();
        if (min != null) {
            minValue = min.value();
            pendingMinReset.compareAndSet(min, null);
        }
        EnvelopeReset max = pendingMaxReset.get();
        if (max != null) {
            maxValue = max.value();
            pendingMaxReset.compareAndSet(max, null);
        }
    }

    /** Last force the watchdog saw, Float.NaN if none yet. */
    public float getLastForce() {
        return lastForce;
    }

    /**
     * Reader thread. Copied, since the device hands every observer the same list; not
     * {@code List.copyOf}, which refuses the null element a broken driver can deliver and would
     * move that crash off the measurement thread, whose catch is what stops the motor.
     * <p>
     * A full queue is the loop {@link #QUEUE_CAPACITY_BATCHES} batches behind the force: alive, but
     * no longer watching the specimen. The first refused batch is handled like the loop's own death
     * (I3): de-energize on this thread, then {@link RunEvent.WatchdogFailed}, which ends the run
     * {@code FAULT}. Later batches are dropped and counted; the run is already over.
     */
    @Override
    public void update(List<Measurement> measurements) {
        if (measurements.isEmpty()) {
            return;
        }
        if (inbound.offer(new ArrayList<>(measurements))) {
            return;
        }
        if (droppedBatches.getAndIncrement() == 0) {
            inboundOverflowed();
        }
    }

    /**
     * Never throws: it runs on the driver's reader thread, which must keep delivering to the other
     * observers. Catches Throwable for the same reason the loop's own catch does.
     */
    private void inboundOverflowed() {
        String reason = "load cell measurement loop fell " + QUEUE_CAPACITY_BATCHES
                + " batches behind the sensor, the force watchdog is no longer current";
        try {
            logger.error("{}; de-energizing, the CSV {} will have a hole", reason, filePath);
            // 1. De-energize first: every detector is deciding on a force seconds old.
            SafeStopResult result = emergencyStop.stop(reason);
            if (result.needsOperatorAttention()) {
                logger.error("Measurement queue overflow AND the motor stop could not be verified: {}", result.detail());
            }
        } catch (Throwable t) {
            logger.error("Could not de-energize after the measurement queue overflowed", t);
        }
        // 2. Then tell the run: a dead watchdog in all but name, so the same event and the same FAULT.
        try {
            events.accept(new RunEvent.WatchdogFailed(reason));
        } catch (Throwable t) {
            logger.error("Could not tell the run loop about the measurement queue overflow", t);
        }
    }

    protected void run() {
        boolean connected = false;
        // Only a throw from inside the loop may emergency-stop; after a normal loop exit the runner
        // is already shutting the motor down itself.
        boolean loopStoppedNormally = false;

        try {
            trace = new TraceWriter(traceOutput.open(filePath), filePath, events,
                    "trace-writer-" + Path.of(filePath).getFileName());
            loadCellDevice.connect();
            connected = true;
            loadCellDevice.registerObserver(this);

            while (running) {
                // Applied here rather than by the caller: see markSensorRecovered().
                if (recoveryRequested) {
                    applyRecoveryReset();
                }

                if (holding) {
                    // Post-loss samples are dropped: no detector has vouched for them, and keeping
                    // a hold's worth would only grow the queue toward its bound.
                    inbound.clear();
                    Thread.sleep(PARK_INTERVAL_MS);
                    continue;
                }

                if (gating) {
                    checkGateDeadline();
                }

                List<Measurement> measurements = takeDrain();
                if (measurements == null) {
                    // The only branch still reached once the sensor is dead. `running` is re-read
                    // so a normal teardown is not mistaken for a silent sensor.
                    if (noDataTimedOut() && running) {
                        // No break: sensorLost() parks the loop so a reconnect resumes into the same file.
                        sensorLost(describeSilence());
                    }
                    continue;
                }

                // Recorded before the batch is processed so a slow disk cannot look like a late
                // sensor: the next poll's timeout counts from here, not from the end of processing.
                long arrivalNanos = watchdogClock.getAsLong();
                // The first drain of a stream, fresh or reconnected, has no predecessor to time.
                boolean timed = watchdogArmed && dataSeen;
                String sparseDrain = timed ? voteOnSampleInterval(arrivalNanos, measurements.size()) : null;
                // Spread like the sample-interval vote's, so a loop stall that backs drains up is not
                // a held value: a stall's backlog arrives as one dense drain.
                long sampleNanos = timed ? (arrivalNanos - lastDataNanos) / measurements.size() : 0;
                lastDataNanos = arrivalNanos;
                dataSeen = true;
                firstSampleSeen.countDown();

                // Handed to the writer before any detector runs, and never waited on: the whole
                // batch reaches the file even when it trips one, and a slow disk delays no decision.
                trace.offer(rowsBeforeFirstNull(measurements));

                // First fault of the batch wins: the samples leading up to a sensor loss are the
                // most interesting ones in the incident file.
                String fault = null;
                Float ceilingCrossing = null;
                for (Measurement measurement : measurements) {
                    requireSample(measurement);

                    // Per sample, never per batch: one drain can carry 50 measurements.
                    String sampleFault = inspectSample(measurement.force(), sampleNanos);
                    if (fault == null) {
                        fault = sampleFault;
                    }

                    if (gating) {
                        scoreGateSample(measurement.force(), sparseDrain);
                    }

                    // Checked even while gating: the ceiling is about the rig, not about trusting
                    // the stream enough to steer the motor.
                    if (ceilingCrossing == null && Float.isFinite(measurement.force())
                            && measurement.force() > testContext.getForceCeiling()) {
                        ceilingCrossing = measurement.force();
                    }

                    // The earliest crossing in the drain decides; a non-finite sample decides nothing.
                    if (!gating) {
                        edge.observe(measurement.force());
                    }
                }

                // After the per-sample detectors, whose reason names the sample that tripped.
                if (fault == null && sparseDrain != null && sparseVotes >= SPARSE_DRAIN_VOTE_REQUIRED) {
                    fault = sparseDrain + " in " + sparseVotes + " of the last "
                            + SPARSE_DRAIN_VOTE_WINDOW + " drains";
                }

                if (ceilingCrossing != null && running && !ceilingTripped) {
                    edge.discard();
                    forceCeilingCrossed(ceilingCrossing);
                    continue;
                }

                if (fault != null && running) {
                    edge.discard();
                    sensorLost(fault);
                    continue;
                }

                // An unproven stream is written and scored but must not move the motor.
                if (gating) {
                    edge.discard();
                    continue;
                }

                edge.emit();
            }
            // Must stay the first statement after the loop, so a throw from the body skips it.
            loopStoppedNormally = true;
        } catch (InterruptedException e) {
            // The normal stop() path; not a fault, so the motor is left to the runner's own shutdown.
            Thread.currentThread().interrupt();
        } catch (Throwable e) {
            // Throwable: an Error (OutOfMemoryError, a LinkageError from a mismatched driver jar)
            // ends the watchdog just as surely as an exception, and must stop the motor the same way.
            logger.error("Load cell thread failed", e);

            if (loopStoppedNormally) {
                // Force was watched while the motor could run: a data problem, not a safety one.
                logger.error("Load cell measurement data may be incomplete in {}", filePath);
            } else {
                // Nobody watches the force limits any more: de-energize first, tell the runner after.
                SafeStopResult result = emergencyStop.stop("load cell thread failed: " + e.getMessage());
                if (result.coasting()) {
                    // The stop worked, only the watchdog is gone: warn, do not claim a failed stop.
                    logger.warn("Load cell thread failed; motor de-energized and coasting down at {} rpm", result.motorSpeedRpm());
                } else if (result.needsOperatorAttention()) {
                    logger.error("Load cell thread failed AND motor stop could not be verified: {}", result.detail());
                }

                // Its own event, never a crossing: the run loop ends the run as a FAULT.
                try {
                    events.accept(new RunEvent.WatchdogFailed("load cell thread failed: " + e.getMessage()));
                } catch (Exception signalFailure) {
                    logger.error("Could not tell the run loop about a load cell thread failure", signalFailure);
                }
            }
        } finally {
            if (connected) {
                // One line per run, zero or not: what the sample-interval bound's bench check reads.
                try {
                    logger.info("Load cell stream ended: {} sparse drain(s) this run, driver discarded {} "
                            + "reading(s) on the last stream", sparseDrainCount, loadCellDevice.getDroppedSampleCount());
                } catch (Exception e) {
                    logger.warn("Could not summarise the load cell stream", e);
                }
            }

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

            // Closed after the observer is gone and the device is down, so no batch can still
            // arrive; close() writes the queued tail first.
            TraceWriter openTrace = trace;
            trace = null;
            if (openTrace != null) {
                try {
                    openTrace.close();
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
     * Blocks until a batch arrives or the no-data detector's timeout runs out, then takes every
     * batch queued by then as one drain, so a backlog is decided once, as it was from the buffer.
     *
     * @return the drain, or null when the poll ran out
     */
    private List<Measurement> takeDrain() throws InterruptedException {
        List<Measurement> first = inbound.poll(pollTimeoutNanos(), TimeUnit.NANOSECONDS);
        if (first == null || inbound.isEmpty()) {
            return first;
        }
        List<List<Measurement>> rest = new ArrayList<>();
        inbound.drainTo(rest);
        List<Measurement> drain = new ArrayList<>(first);
        for (List<Measurement> batch : rest) {
            drain.addAll(batch);
        }
        return drain;
    }

    /**
     * Armed, the timeout is what is left of {@link #NO_DATA_TIMEOUT_MS} since the last batch was
     * taken, so time spent processing it counts against the silence; zero once that has run out.
     * Disarmed, {@link #PARK_INTERVAL_MS}. While a gate is open it never sleeps past the gate's
     * deadline.
     */
    private long pollTimeoutNanos() {
        long timeout = watchdogArmed && dataSeen
                ? TimeUnit.MILLISECONDS.toNanos(NO_DATA_TIMEOUT_MS) - (watchdogClock.getAsLong() - lastDataNanos)
                : TimeUnit.MILLISECONDS.toNanos(PARK_INTERVAL_MS);
        if (gating && gateFuture != null) {
            timeout = Math.min(timeout, gateDeadlineNanos - gateClock.getAsLong());
        }
        return Math.max(0, timeout);
    }

    /**
     * Waits until the measurement loop itself has processed a sample, which is what arms its no-data
     * detector. A fresh sample at the device proves only that the driver delivers.
     *
     * @return false on timeout
     */
    public boolean awaitFirstSample(long timeoutMs) throws InterruptedException {
        return firstSampleSeen.await(timeoutMs, TimeUnit.MILLISECONDS);
    }

    /**
     * True when the stream has been silent longer than {@link #NO_DATA_TIMEOUT_MS}. Disarmed until
     * the first measurement, since connect and first USB frame outlast the timeout; a reconnect is
     * the same slow start, hence {@link #watchdogArmed}.
     */
    private boolean noDataTimedOut() {
        if (!watchdogArmed || !dataSeen) {
            return false;
        }

        return watchdogClock.getAsLong() - lastDataNanos > TimeUnit.MILLISECONDS.toNanos(NO_DATA_TIMEOUT_MS);
    }

    /**
     * The no-data trip reason, including the driver's stream failure when it has one. Never throws,
     * so the diagnostic cannot stop the escalation; catches Throwable for linkage errors from a
     * mismatched dscusb jar.
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
     * The sample-interval detector's vote for one drain: the time since the previous drain spread
     * over the samples this one carried. Bounds density, not latency: a bus delivering bursts well
     * inside the no-data timeout passes, as it should, since that timeout already bounds the wait.
     *
     * @return this drain described as sparse, or null when its samples came fast enough
     */
    private String voteOnSampleInterval(long arrivalNanos, int samples) {
        long sincePreviousDrainNanos = arrivalNanos - lastDataNanos;
        double millisPerSample = sincePreviousDrainNanos / 1e6 / samples;
        long bound = gates.maxSampleIntervalMillis();
        boolean sparse = millisPerSample > bound;

        if (sparseDrainVotes[sparseDrainVoteIndex]) {
            sparseVotes--;
        }
        sparseDrainVotes[sparseDrainVoteIndex] = sparse;
        if (sparse) {
            sparseVotes++;
        }
        sparseDrainVoteIndex = (sparseDrainVoteIndex + 1) % SPARSE_DRAIN_VOTE_WINDOW;

        if (!sparse) {
            return null;
        }
        String described = String.format(Locale.ROOT,
                "load cell delivered %d sample(s) %d ms after the previous drain (%.1f ms per sample), "
                        + "slower than the %d ms bound",
                samples, TimeUnit.NANOSECONDS.toMillis(sincePreviousDrainNanos), millisPerSample, bound);

        // Logged even below the trip, so a stream that sits at one or two votes in five is visible.
        long count = ++sparseDrainCount;
        if (count == 1 || arrivalNanos - sparseDrainLoggedNanos
                >= TimeUnit.MILLISECONDS.toNanos(SPARSE_DRAIN_LOG_INTERVAL_MS)) {
            sparseDrainLoggedNanos = arrivalNanos;
            logger.warn("Sparse load cell drain, {} this run, {} of the last {}: {}",
                    count, sparseVotes, SPARSE_DRAIN_VOTE_WINDOW, described);
        }
        return described;
    }

    /** Unit-test entry point for a sample that adds no time to a frozen run. */
    String inspectSample(float force) {
        return inspectSample(force, 0);
    }

    /**
     * Runs both per-sample detectors and folds a plausible sample into the measured envelope.
     *
     * @param sampleNanos this sample's share of the time since the previous drain, 0 for the first
     *                    drain of a stream; the frozen detector's time bound sums it over the repeats
     * @return the trip reason, or null when the sample is acceptable
     */
    // Package-private: production code must only call this from the measurement loop.
    String inspectSample(float force, long sampleNanos) {
        String reason = null;

        // Raw bits, not ==: == says 0.0f equals -0.0f (two readings the hardware genuinely produces)
        // and that NaN never equals NaN (so a stuck NaN would never read as stuck).
        int bits = Float.floatToRawIntBits(force);
        if (hasPreviousForce && bits == previousForceBits) {
            frozenSampleCount++;
            frozenNanos += sampleNanos;
            if (frozenSampleCount >= FROZEN_SAMPLE_LIMIT
                    || frozenNanos >= TimeUnit.MILLISECONDS.toNanos(gates.maxFrozenMillis())) {
                reason = "force frozen at " + force + " N for " + frozenSampleCount
                        + " consecutive bit-identical samples over "
                        + TimeUnit.NANOSECONDS.toMillis(frozenNanos) + " ms";
            }
        } else {
            frozenSampleCount = 0;
            frozenNanos = 0;
        }

        boolean implausible = isImplausible(force);

        applyPendingEnvelopeResets();

        // Math.min/max absorb NaN: one implausible sample would pin both bounds at NaN and silently
        // disable the shut-off. See doc/dev/backend/safety-and-recovery.md.
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

    /** One sample's plausibility vote; a single corrupted USB frame must not abort a run. */
    private boolean isImplausible(float force) {
        if (Float.isNaN(force) || Float.isInfinite(force)) {
            return true;
        }

        if (Math.abs(force) > implausibleForceNewton) {
            return true;
        }

        float previous = lastForce;
        if (Float.isNaN(previous)) {
            return false;
        }

        // Only rises vote: a fast drop is a specimen breaking, so never use Math.abs(force - previous).
        // Magnitudes, so loading in either direction counts and a release toward zero does not.
        float rise = Math.abs(force) - Math.abs(previous);
        return rise > implausibleRiseNewton;
    }

    /**
     * Single escalation path for all four detectors. The step order is a safety invariant (I3):
     * the motor stops on this thread before anything is recorded, and the run loop hears of the loss
     * last, as a {@link RunEvent.SensorLost} that commits SENSOR_LOST on this thread.
     */
    private void sensorLost(String reason) {
        if (sensorLostTripped) {
            // Backstop; callers already stop feeding samples in on the first trip.
            return;
        }
        sensorLostTripped = true;
        float lastKnownForce = lastForce;

        // The drop count tells a cell that had been failing frames from one that failed at once.
        logger.error("Load cell watchdog tripped: {}. Last known force: {} N, driver discarded {} reading(s), "
                        + "{} sparse drain(s) this run",
                reason, lastKnownForce, loadCellDevice.getDroppedSampleCount(), sparseDrainCount);

        // 1. De-energize first: until then the motor pulls blind. A repeat from cleanup() replays
        // the recorded escalation.
        SafeStopResult result = emergencyStop.stop("load cell lost: " + reason);
        if (result.coasting()) {
            // The stop worked, only the watchdog is gone: warn, do not claim a failed stop.
            logger.warn("Load cell lost; motor de-energized and coasting down at {} rpm", result.motorSpeedRpm());
        } else if (result.needsOperatorAttention()) {
            logger.error("Load cell lost AND motor stop could not be verified: {}", result.detail());
        }

        try {
            // 2. Hand over the pre-loss samples still queued, without running the detectors again.
            drainPendingSamples();
            // 3. Flush, bounded, so a crash during the hold keeps the incident tail; a stalled disk
            // delays the gap record by the bound and no more.
            if (!trace.flush(TraceWriter.FLUSH_BOUND_MS)) {
                logger.error("The samples leading up to the load cell loss may not be on disk yet in {}", filePath);
            }
        } catch (Exception e) {
            logger.error("Could not persist the samples leading up to the load cell loss in {}", filePath, e);
        }

        // 4. Durable before anything below can fail, so an aborted process still records the hold.
        gapRecorder.beginGap(reason, lastKnownForce);

        // 5. Parked, not stopped: `running` stays true so a reconnect resumes into the same file.
        // Before the event, so a reconnector the loop's hold entry starts never finds it unparked.
        holding = true;

        // 6. Tell the run: the motor is stopped, the tail is on disk and the gap is open, so the
        // loop's hold entry is bookkeeping only. The sink commits SENSOR_LOST on this thread.
        try {
            events.accept(new RunEvent.SensorLost(reason, lastKnownForce));
        } catch (Exception e) {
            logger.error("Could not tell the run loop about a load cell loss", e);
        }

        // 7. Fail a gate in flight now rather than at its deadline; its reconnector reports that.
        completeGate(false, "the sensor was lost again during the gate: " + reason);
    }

    /**
     * A hard trip, not an incident: the sensor works and has measured a force the run must never
     * reach, so there is nothing to recover and the run ends as a FAULT.
     */
    private void forceCeilingCrossed(float force) {
        ceilingTripped = true;
        RunEvent.CeilingTripped trip = new RunEvent.CeilingTripped(force, testContext.getForceCeiling());
        String reason = trip.detail();
        logger.error("Force ceiling tripped: {}", reason);

        SafeStopResult result = emergencyStop.stop(reason);
        if (result.needsOperatorAttention()) {
            logger.error("Force ceiling tripped AND the motor stop could not be verified: {}", result.detail());
        }

        try {
            if (!trace.flush(TraceWriter.FLUSH_BOUND_MS)) {
                logger.error("The samples leading up to the force ceiling trip may not be on disk yet in {}", filePath);
            }
        } catch (Exception e) {
            logger.error("Could not flush the samples leading up to the force ceiling trip in {}", filePath, e);
        }

        try {
            events.accept(trip);
        } catch (Exception e) {
            logger.error("Could not end the run after a force ceiling trip", e);
        }
    }

    /**
     * The rows a batch can still give the trace. A broken driver can deliver a null sample; the
     * samples before it are written, as they were when the loop wrote each row itself.
     */
    private static List<Measurement> rowsBeforeFirstNull(List<Measurement> batch) {
        int firstNull = batch.indexOf(null);
        return firstNull < 0 ? batch : batch.subList(0, firstNull);
    }

    /**
     * Fails a null sample on this thread, whose catch is what stops the motor, with the message the
     * inline CSV write used to fail it with: the run log and the FAULT reason name it.
     */
    @SuppressWarnings("ResultOfMethodCallIgnored")
    private static void requireSample(Measurement measurement) {
        measurement.timestamp();
    }

    /** Hands the queued samples to the trace without inspecting them; loss path only. */
    private void drainPendingSamples() {
        List<List<Measurement>> pending = new ArrayList<>();
        inbound.drainTo(pending);

        for (List<Measurement> batch : pending) {
            List<Measurement> rows = rowsBeforeFirstNull(batch);
            trace.offer(rows);
            if (rows.size() < batch.size()) {
                // A null sample ended the inline write here; nothing after it was ever written.
                logger.error("Could not persist the samples leading up to the load cell loss in {}: a null sample", filePath);
                return;
            }
        }
    }

    /**
     * Announces that the driver has handed back a live stream. Called from the reconnector thread,
     * so it only raises a flag; the loop applies {@link #applyRecoveryReset()} on its next iteration.
     */
    public void markSensorRecovered() {
        watchdogArmed = false;
        recoveryRequested = true;
    }

    /** Runs on the measurement thread only. */
    private void applyRecoveryReset() {
        recoveryRequested = false;

        // Disarms the no-data detector until the first post-reconnect sample; see noDataTimedOut().
        dataSeen = false;
        lastDataNanos = 0;

        frozenSampleCount = 0;
        frozenNanos = 0;
        hasPreviousForce = false;
        Arrays.fill(plausibilityVotes, false);
        plausibilityVoteIndex = 0;
        implausibleVotes = 0;
        Arrays.fill(sparseDrainVotes, false);
        sparseDrainVoteIndex = 0;
        sparseVotes = 0;
        sensorLostTripped = false;

        // Re-seeded, not carried across the gap: cyclic tests derive their next limit from this
        // envelope, and the specimen may have crept during the hold.
        pendingMinReset.set(null);
        pendingMaxReset.set(null);
        minValue = (float) testContext.getLowerLimit();
        maxValue = (float) testContext.getUpperLimit();

        // Samples from the dead session must not be scored by the gate.
        inbound.clear();

        watchdogArmed = true;
    }

    /**
     * Opens the recovery gate: samples flow into the one continuous CSV again, but no limit signal is
     * emitted until an unbroken run of acceptable samples has lasted {@code plausibilityGateMillis}
     * and held {@code plausibilityGateMinSamples}.
     * <p>
     * Evaluated in the measurement loop, not a temporary observer, so the gate's samples land in the
     * CSV and nothing races the loop's queue.
     * <p>
     * The gate never tares: zeroing would discard the real force the specimen is under.
     *
     * @param envelopeNewton the force envelope the drift gate is a fraction of; the caller has
     *                       already floored it at {@code minEnvelopeNewton}
     */
    public CompletableFuture<GateResult> beginRecoveryGate(float lastKnownForce, double envelopeNewton) {
        CompletableFuture<GateResult> future = new CompletableFuture<>();

        gateBaseline = lastKnownForce;
        gateEnvelope = envelopeNewton;
        gateDeadlineNanos = gateClock.getAsLong() + TimeUnit.MILLISECONDS.toNanos(
                2 * gates.plausibilityGateMillis() + GATE_DEADLINE_SLACK_MS);
        gateRunStartNanos = 0;
        gateRunSamples = 0;
        gateFirstForce = Float.NaN;
        gateHasPreviousBits = false;
        gateFrozenRun = 0;
        gateLastFailure = null;
        gateFuture = future;

        // Order matters: the volatile `gating` write publishes the gate fields, then clearing
        // `holding` releases the loop.
        gating = true;
        holding = false;
        return future;
    }

    /**
     * Leaves hold and gate mode so limit signals resume. The watchdog is already armed, which must
     * happen before the caller re-enables the drive.
     */
    public void endHold() {
        gating = false;
        holding = false;
    }

    public boolean isHolding() {
        return holding;
    }

    /** Unit-test entry point; the loop itself scores each sample with its drain's verdict. */
    void scoreGateSample(float force) {
        scoreGateSample(force, null);
    }

    /**
     * One sample's verdict inside the gate. Any failing criterion resets the continuity timer, so the
     * gate measures an unbroken run of good samples rather than a good average. The run must both
     * last the window and hold the minimum count. A sample of a sparse drain fails however plausible
     * it is.
     *
     * @param drainFailure why the drain this sample arrived in fails the gate, or null
     */
    private void scoreGateSample(float force, String drainFailure) {
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
        } else if (Math.abs(force) > implausibleForceNewton) {
            failure = "reading " + force + " N is outside the machine range";
        } else if (gateFrozenRun >= GATE_FROZEN_RUN) {
            failure = "force frozen at " + force + " N for " + (gateFrozenRun + 1)
                    + " consecutive bit-identical samples";
        } else {
            float drift = Math.abs(force - gateBaseline);
            if (!(drift < gates.driftFraction() * gateEnvelope)) {
                failure = "drifted " + drift + " N from the last known " + gateBaseline
                        + " N, more than " + gates.driftFraction() + " of the "
                        + gateEnvelope + " N envelope";
            }
        }
        if (failure == null) {
            failure = drainFailure;
        }

        if (failure != null) {
            gateLastFailure = failure;
            gateRunStartNanos = 0;
            gateRunSamples = 0;
            return;
        }

        long now = gateClock.getAsLong();
        if (gateRunStartNanos == 0) {
            gateRunStartNanos = now;
        }
        gateRunSamples++;
        if (now - gateRunStartNanos >= TimeUnit.MILLISECONDS.toNanos(gates.plausibilityGateMillis())
                && gateRunSamples >= gates.plausibilityGateMinSamples()) {
            completeGate(true, "the reconnected stream stayed plausible for "
                    + TimeUnit.NANOSECONDS.toMillis(now - gateRunStartNanos) + " ms over "
                    + gateRunSamples + " samples (gate " + gates.plausibilityGateMillis() + " ms, "
                    + gates.plausibilityGateMinSamples() + " samples)");
        }
    }

    /** Fails a gate that never gathered an unbroken run, including one that got no samples at all. */
    private void checkGateDeadline() {
        if (gateFuture == null || gateClock.getAsLong() - gateDeadlineNanos < 0) {
            return;
        }

        String why = gateLastFailure != null ? gateLastFailure
                : Float.isNaN(gateFirstForce) ? "no measurement arrived after the reconnect"
                : "the stream never held a plausible run of the gate's length and sample count";
        completeGate(false, why);
    }

    /** Drift is logged pass or fail: it is the data for retuning the drift fraction. */
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
