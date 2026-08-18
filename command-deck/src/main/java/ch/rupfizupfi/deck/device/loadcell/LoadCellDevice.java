package ch.rupfizupfi.deck.device.loadcell;

import ch.rupfizupfi.deck.device.Device;
import ch.rupfizupfi.deck.device.api.LoadCellStream;
import ch.rupfizupfi.deck.device.api.LoadCellStreamProvider;
import ch.rupfizupfi.deck.device.api.Measurement;
import ch.rupfizupfi.deck.device.api.StreamFailure;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

public class LoadCellDevice extends Device {
    Logger log = Logger.getLogger(LoadCellDevice.class.getName());

    private final LoadCellStreamProvider streamProvider;

    /** Volatile because the watchdog thread reads it through {@link #getStreamFailure()}. */
    private volatile LoadCellStream stream;
    private final List<MeasurementObserver> observers = new CopyOnWriteArrayList<>();
    private Thread dataThread;
    private volatile boolean isRunning = false;

    /**
     * nanoTime of the last non-empty batch handed to observers. nanoTime, not currentTimeMillis:
     * freshness must survive NTP steps and manual clock changes on the bench machine.
     */
    private volatile long lastDataNanos = 0;

    /** Poll interval of {@link #awaitFreshMeasurement(long)}; the reader loop itself ticks every 20 ms. */
    private static final long FRESHNESS_POLL_INTERVAL_MS = 20;

    public LoadCellDevice(LoadCellStreamProvider streamProvider) {
        this.streamProvider = streamProvider;
    }

    @Override
    protected void openConnection() {
        log.info("openConnection entered");
        // A reopened device must not inherit the previous session's freshness, otherwise the
        // first watchdog tick after a reconnect would pass on data from the old connection.
        lastDataNanos = 0;
        // A new instance every time: a stopped stream can never be restarted.
        stream = streamProvider.open();
        stream.startReading();
        isRunning = true;
        dataThread = new Thread(this::readData);
        dataThread.start();
    }

    @Override
    protected void closeConnection() {
        log.info("closeConnection entered");
        isRunning = false;
        if (dataThread != null) {
            try {
                dataThread.join(); // Wait for the thread to finish
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            dataThread = null;
            log.info("dataThread joined");
        }
        if (stream != null) {
            stream.stopReading();
            stream = null;
        }
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
    }

    private void notifyObservers(List<Measurement> measurements) {
        for (MeasurementObserver observer : observers) {
            // Observers run on the reader thread; one misbehaving consumer must not stop the
            // remaining observers -- nor the measurement collection itself -- from being served.
            try {
                observer.update(measurements);
            } catch (RuntimeException e) {
                log.log(Level.WARNING, "Measurement observer " + observer.getClass().getName()
                        + " threw, continuing with the remaining observers", e);
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
            // 0 means "never any data", not "arrived at the epoch" -- treating it as a very old
            // timestamp would be indistinguishable from a stale reading and could mask a dead sensor.
            return false;
        }

        return System.nanoTime() - last <= TimeUnit.MILLISECONDS.toNanos(maxAgeMs);
    }

    /**
     * Why the driver's reader thread stopped delivering, or null while it is still reading.
     * Counterpart to {@link #isDataFlowing(long)}: silence looks the same from the outside whatever
     * caused it, so without asking the driver a sensor loss can only be reported as a timeout.
     */
    public String getStreamFailure() {
        LoadCellStream current = stream;
        if (current == null) {
            return "load cell stream is not open";
        }

        if (current.isReading()) {
            return null;
        }

        StreamFailure error = current.lastError();
        if (error == null) {
            // The driver records an error for every abnormal exit, so a clean stop while the device
            // still considers itself open is a bug, not a hardware fault.
            return "load cell reader thread stopped without reporting an error";
        }

        // The driver's code is kept verbatim: it maps onto the READCOMMAND table in the vendor docs.
        // A null code means the failure was not one the driver has a table entry for.
        if (error.driverCode() != null) {
            return "load cell driver error " + error.driverCode() + ": " + error.message();
        }

        String detail = error.message();
        return "load cell reader thread died: " + error.failureType()
                + (detail == null ? "" : ": " + detail);
    }

    /**
     * Blocks until a batch arrives that is strictly newer than the moment of the call, or the timeout
     * expires. Returns true only if fresh data actually arrived. Never throws: on interrupt it restores
     * the interrupt flag and returns false.
     */
    public boolean awaitFreshMeasurement(long timeoutMs) {
        // Taken before anything else: only batches recorded after this instant can satisfy the
        // wait, so an already-stale lastDataNanos can never produce a false positive.
        long since = System.nanoTime();
        long deadline = since + TimeUnit.MILLISECONDS.toNanos(timeoutMs);

        while (true) {
            long last = lastDataNanos;
            // The 0 check is not redundant: nanoTime's origin is arbitrary and may be negative,
            // in which case the "never any data" sentinel would compare as newer than `since`.
            if (last != 0 && last - since > 0) {
                return true;
            }

            // nanoTime deadline rather than an iteration count, so a slow or descheduled poll
            // does not silently stretch the caller's timeout.
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
        log.info("readData entered");

        boolean interrupted = false;
        boolean readerFailureLogged = false;
        while (isRunning) {
            try {
                // Logged once, not per iteration, so the cause is not buried under 50 lines a second.
                // The loop continues afterwards: the driver's queue can still hold the samples taken
                // before it died, which are the ones worth having.
                if (!readerFailureLogged) {
                    String failure = getStreamFailure();
                    if (failure != null) {
                        readerFailureLogged = true;
                        // No stack trace: the API reports the cause as data, not as a Throwable, so
                        // that a driver-less build can report one too. The message already carries
                        // the driver code or the failure type.
                        log.log(Level.SEVERE, failure + "; no further measurements will arrive");
                    }
                }

                var measurements = stream.getNextValues();
                if (!measurements.isEmpty()) {
                    // Recorded before the fan-out so the timestamp reflects when the hardware
                    // delivered, not how long the observers took.
                    lastDataNanos = System.nanoTime();
                    notifyObservers(measurements);
                }

                Thread.sleep(20);
            } catch (InterruptedException e) {
                interrupted = true;
                Thread.currentThread().interrupt();
                break;
            } catch (RuntimeException e) {
                // One bad batch must not end measurement collection, so the loop continues.
                // Detecting a *persistently* broken stream is deliberately not done here: the
                // freshness tracking above (isDataFlowing / the LoadCellThread watchdog) is what
                // surfaces a reader that keeps failing.
                log.log(Level.WARNING, "Ignoring error while reading load cell values, continuing", e);

                // Keeps the loop cadence when getNextValues() throws immediately every time,
                // which would otherwise burn a core while the rig is under load.
                try {
                    Thread.sleep(20);
                } catch (InterruptedException ie) {
                    interrupted = true;
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }

        // Logged at INFO so a reader that stops for any reason leaves a trace in the server log
        // instead of dying silently while the device still reports itself connected.
        log.info("readData finished (" + (interrupted ? "interrupted" : "isRunning went false")
                + "), data was " + (lastDataNanos == 0 ? "never received" : "received at least once"));
    }
}