package ch.rupfizupfi.deck.device.loadcell;

import ch.rupfizupfi.deck.device.Device;
import ch.rupfizupfi.deck.device.api.LoadCellStream;
import ch.rupfizupfi.deck.device.api.Measurement;
import ch.rupfizupfi.deck.device.api.StreamFailure;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;

public class LoadCellDevice extends Device {
    private static final Logger log = LoggerFactory.getLogger(LoadCellDevice.class);

    private final SessionAwareStreamProvider streamProvider;

    /** Volatile because the watchdog thread reads it through {@link #getStreamFailure()}. */
    private volatile LoadCellStream stream;
    private final List<MeasurementObserver> observers = new CopyOnWriteArrayList<>();
    private Thread dataThread;
    private volatile boolean isRunning = false;
    private final AtomicInteger readerThreads = new AtomicInteger();

    /** nanoTime of the last non-empty batch handed to observers; nanoTime survives wall-clock steps. */
    private volatile long lastDataNanos = 0;

    /** Poll interval of {@link #awaitFreshMeasurement(long)}; the reader loop itself ticks every 20 ms. */
    private static final long FRESHNESS_POLL_INTERVAL_MS = 20;

    /**
     * Bounded so a reader wedged in a native driver call cannot pin the instance monitor; see
     * {@code FrequencyInverterDevice.POLL_THREAD_JOIN_TIMEOUT_MS}.
     */
    private static final long READER_JOIN_TIMEOUT_MS = 2000;

    /**
     * Minimum spacing of the repeat WARNs for a failing observer and for rising dropped-sample counts:
     * at the 20 ms cadence either used to log fifty lines a second.
     */
    static final long REPEAT_LOG_INTERVAL_NANOS = TimeUnit.SECONDS.toNanos(10);

    /** Clock for the log throttles only; freshness stays on {@link System#nanoTime()}. */
    private final LongSupplier logClock;

    /** Observers whose last update threw; entry removed (and announced) when one succeeds again. */
    private final Map<MeasurementObserver, ObserverFailures> failingObservers = new ConcurrentHashMap<>();

    /** A plain driver provider is adapted by {@code DeviceService}; see {@link SessionAwareStreamProvider}. */
    public LoadCellDevice(SessionAwareStreamProvider streamProvider) {
        this(streamProvider, System::nanoTime);
    }

    /** Test seam: {@code logClock} drives only the repeat-log throttles. */
    LoadCellDevice(SessionAwareStreamProvider streamProvider, LongSupplier logClock) {
        this.streamProvider = streamProvider;
        this.logClock = logClock;
    }

    @Override
    protected void openConnection() {
        log.info("openConnection entered");
        // Must not touch `observers`: they live on the Device so a reset() is transparent to them.

        // A reopened device must not inherit the previous session's freshness.
        lastDataNanos = 0;
        // A new instance every time: a stopped stream cannot be restarted. See SessionAwareStreamProvider.
        LoadCellStream fresh = streamProvider.open(reopening);
        // The field only ever holds a stream that is reading.
        try {
            fresh.startReading();
        } catch (RuntimeException | Error t) {
            try {
                // A stream that never started still owes its driver a release.
                fresh.stopReading();
            } catch (Throwable release) {
                log.warn("Releasing a load cell stream that failed to start failed as well", release);
            }
            throw t;
        }
        stream = fresh;
        isRunning = true;
        // Numbered, since a reconnect can briefly leave a stranded reader alongside the new one.
        dataThread = new Thread(this::readData, "load-cell-reader-" + readerThreads.incrementAndGet());
        dataThread.start();
    }

    @Override
    protected void closeConnection() {
        log.info("closeConnection entered");
        isRunning = false;
        if (dataThread != null) {
            // Interrupt first so the reader leaves its 20 ms sleep at once.
            dataThread.interrupt();
            try {
                // Bounded, so a reader stuck in a native call cannot pin the instance monitor.
                // Abandoning it is safe: readData() stops publishing once its stream is not current.
                dataThread.join(READER_JOIN_TIMEOUT_MS);
                if (dataThread.isAlive()) {
                    log.warn("Load cell reader did not stop within {} ms, abandoning it",
                            READER_JOIN_TIMEOUT_MS);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            dataThread = null;
            log.info("dataThread released");
        }
        // Field cleared first and the stop absorbed, so the steps below always run.
        LoadCellStream closing = stream;
        stream = null;
        if (closing != null) {
            try {
                closing.stopReading();
            } catch (RuntimeException | Error t) {
                log.warn("Stopping the load cell stream failed, releasing it anyway", t);
            }
        }
        // The only signal a buffering observer such as ForceBroadcaster gets that no batch follows.
        flushObservers();
        // Cleared after the reader thread has been joined, so a last in-flight batch cannot
        // re-arm the timestamp on a closed device.
        lastDataNanos = 0;

        log.info("closeConnection finished");
    }

    public void registerObserver(MeasurementObserver observer) {
        observers.add(observer);
    }

    public void unregisterObserver(MeasurementObserver observer) {
        observers.remove(observer);
        failingObservers.remove(observer);
    }

    private void notifyObservers(List<Measurement> measurements) {
        for (MeasurementObserver observer : observers) {
            // Observers run on the reader thread; one throwing must not stop the others or the reader.
            try {
                observer.update(measurements);
            } catch (RuntimeException e) {
                observerFailed(observer, e);
                continue;
            }
            ObserverFailures recovered = failingObservers.remove(observer);
            if (recovered != null) {
                log.info("Measurement observer {} accepts batches again after {} failed batch(es)",
                        observer.getClass().getName(), recovered.total);
            }
        }
    }

    /**
     * First failure of an outage with its stack trace, then a count at most every
     * {@link #REPEAT_LOG_INTERVAL_NANOS}: a persistently throwing observer sees every batch.
     */
    private void observerFailed(MeasurementObserver observer, RuntimeException e) {
        long now = logClock.getAsLong();
        ObserverFailures failures = failingObservers.get(observer);
        if (failures == null) {
            failingObservers.put(observer, new ObserverFailures(now));
            log.warn("Measurement observer {} threw, continuing with the remaining observers; repeats are "
                    + "summarised at most every {} s", observer.getClass().getName(),
                    TimeUnit.NANOSECONDS.toSeconds(REPEAT_LOG_INTERVAL_NANOS), e);
            return;
        }
        failures.total++;
        failures.sinceLastLog++;
        if (now - failures.lastLogNanos >= REPEAT_LOG_INTERVAL_NANOS) {
            log.warn("Measurement observer {} still throwing: {} more failed batch(es), {} in total; last: {}",
                    observer.getClass().getName(), failures.sinceLastLog, failures.total, e.toString());
            failures.sinceLastLog = 0;
            failures.lastLogNanos = now;
        }
    }

    /** One observer's current outage. Touched only by the reader thread. */
    private static final class ObserverFailures {
        long total = 1;
        long sinceLastLog = 0;
        long lastLogNanos;

        ObserverFailures(long firstFailureNanos) {
            this.lastLogNanos = firstFailureNanos;
        }
    }

    private void flushObservers() {
        for (MeasurementObserver observer : observers) {
            try {
                observer.flush();
            } catch (RuntimeException e) {
                log.warn("Measurement observer {} threw while flushing, continuing with the "
                        + "remaining observers", observer.getClass().getName(), e);
            }
        }
    }

    /** nanoTime of the last non-empty batch delivered to observers; 0 if none since the device opened. */
    public long getLastDataNanos() {
        return lastDataNanos;
    }

    /**
     * True when a measurement batch arrived within the last maxAgeMs.
     * False if none ever arrived since the device opened.
     */
    public boolean isDataFlowing(long maxAgeMs) {
        long last = lastDataNanos;
        if (last == 0) {
            // 0 means "never any data".
            return false;
        }

        return System.nanoTime() - last <= TimeUnit.MILLISECONDS.toNanos(maxAgeMs);
    }

    /**
     * Why the driver's reader thread stopped delivering, or null while it is still reading.
     * Counterpart to {@link #isDataFlowing(long)}, which cannot tell causes of silence apart.
     */
    public String getStreamFailure() {
        return describeFailure(stream);
    }

    /**
     * Same answer for a given stream, so a reader owning a replaced stream reports its own cause.
     */
    private String describeFailure(LoadCellStream current) {
        if (current == null) {
            return "load cell stream is not open";
        }

        if (current.isReading()) {
            return null;
        }

        StreamFailure error = current.lastError();
        if (error == null) {
            // The driver records an error for every abnormal exit, so this is a bug, not a hardware fault.
            return "load cell reader thread stopped without reporting an error";
        }

        // Verbatim: the code maps onto the READCOMMAND table in the vendor docs; null has no entry.
        if (error.driverCode() != null) {
            return "load cell driver error " + error.driverCode() + ": " + error.message();
        }

        String detail = error.message();
        return "load cell reader thread died: " + error.failureType()
                + (detail == null ? "" : ": " + detail);
    }

    /**
     * Readings the driver discarded rather than delivered this session; 0 when the stream is closed.
     * A rising count on a reading stream is the only sign of frames the driver rejects.
     */
    public long getDroppedSampleCount() {
        LoadCellStream current = stream;
        return current == null ? 0 : current.droppedSampleCount();
    }

    /**
     * Blocks until a batch arrives that is strictly newer than the moment of the call, or the timeout
     * expires. Returns true only if fresh data actually arrived. Never throws: on interrupt it restores
     * the interrupt flag and returns false.
     */
    public boolean awaitFreshMeasurement(long timeoutMs) {
        // Only batches recorded after this instant satisfy the wait.
        long since = System.nanoTime();
        long deadline = since + TimeUnit.MILLISECONDS.toNanos(timeoutMs);

        while (true) {
            long last = lastDataNanos;
            // The 0 check is needed: nanoTime may be negative, so the sentinel could compare as newer.
            if (last != 0 && last - since > 0) {
                return true;
            }

            if (System.nanoTime() - deadline >= 0) {
                return false;
            }

            try {
                Thread.sleep(FRESHNESS_POLL_INTERVAL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
    }

    private void readData() {
        // Captured once; the loop exits when the field no longer holds it, so a reader abandoned by
        // closeConnection() never drains the next session's queue alongside the real one.
        final LoadCellStream mine = this.stream;
        log.info("readData entered");

        boolean interrupted = false;
        boolean readerFailureLogged = false;
        long lastLoggedDrops = 0;
        // nanoTime of the last drop WARN; meaningful only once lastLoggedDrops > 0.
        long lastDropLogNanos = 0;
        // Throttles the read-error log to one WARN per outage: at the 20 ms cadence a persistent
        // failure used to write fifty stack traces a second.
        long failedReads = 0;
        while (isRunning && this.stream == mine) {
            try {
                // Logged once. The loop continues: the queue can still hold pre-failure samples.
                if (!readerFailureLogged) {
                    String failure = describeFailure(mine);
                    if (failure != null) {
                        readerFailureLogged = true;
                        // No stack trace: the API reports the cause as data, not as a Throwable.
                        log.error("{}; no further measurements will arrive", failure);
                    }
                }

                // An absorbed fault leaves no other trace than a timestamp jump. The first drop is
                // logged at once, later rises at most every REPEAT_LOG_INTERVAL_NANOS with the delta.
                long drops = mine.droppedSampleCount();
                if (drops != lastLoggedDrops) {
                    long now = logClock.getAsLong();
                    if (lastLoggedDrops == 0 || now - lastDropLogNanos >= REPEAT_LOG_INTERVAL_NANOS) {
                        log.warn("Load cell driver has discarded {} reading(s) this session ({} since the "
                                + "last report); the stream is still reading", drops, drops - lastLoggedDrops);
                        lastLoggedDrops = drops;
                        lastDropLogNanos = now;
                    }
                }

                var measurements = mine.getNextValues();
                if (failedReads > 0) {
                    log.info("Load cell stream answers again after {} failed read(s)", failedReads);
                    failedReads = 0;
                }
                // Re-checked: the drain can straddle a reopen.
                if (!measurements.isEmpty() && this.stream == mine) {
                    // Before the fan-out, so observer time does not skew freshness.
                    lastDataNanos = System.nanoTime();
                    notifyObservers(measurements);
                }

                Thread.sleep(20);
            } catch (InterruptedException e) {
                interrupted = true;
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                // Exception, because the driver's checked exceptions cross LoadCellStream undeclared.
                // A persistently broken stream is the MeasurementLoop watchdog's to detect.
                if (failedReads++ == 0) {
                    log.warn("Error while reading load cell values, continuing; further errors of this "
                            + "outage are logged at debug", e);
                } else {
                    log.debug("Load cell read still failing ({} in a row): {}", failedReads, e.toString());
                }

                // Keeps the cadence when getNextValues() throws immediately every time.
                try {
                    Thread.sleep(20);
                } catch (InterruptedException ie) {
                    interrupted = true;
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }

        String why = interrupted ? "interrupted"
                : this.stream != mine ? "stream was replaced"
                : "isRunning went false";
        log.info("readData finished ({}), data was {}", why,
                lastDataNanos == 0 ? "never received" : "received at least once");
    }
}