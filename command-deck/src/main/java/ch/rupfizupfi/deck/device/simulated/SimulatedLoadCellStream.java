package ch.rupfizupfi.deck.device.simulated;

import ch.rupfizupfi.deck.device.api.LoadCellStream;
import ch.rupfizupfi.deck.device.api.Measurement;
import ch.rupfizupfi.deck.device.api.StreamFailure;

import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Drains samples the {@link SimulatedBench} pushes while this stream is registered.
 * <p>
 * The stream is fed continuously rather than on demand, because the no-data watchdog trips after
 * 250 ms of silence: a stream that only answered when asked would be indistinguishable from a dead
 * sensor whenever the reader thread was descheduled.
 */
public class SimulatedLoadCellStream implements LoadCellStream {

    /** Bounds the queue if nobody drains it. One second at the default tick, then oldest is dropped. */
    private static final int MAX_QUEUED_SAMPLES = 400;

    private final SimulatedBench bench;
    private final Queue<Measurement> pending = new ConcurrentLinkedQueue<>();
    private final AtomicInteger queued = new AtomicInteger();

    private volatile boolean reading = false;
    /** Latched: matches the driver contract that a stopped stream can never be read again. */
    private volatile boolean stopped = false;
    private volatile StreamFailure failure;

    SimulatedLoadCellStream(SimulatedBench bench) {
        this.bench = bench;
    }

    @Override
    public void startReading() {
        if (stopped) {
            throw new IllegalStateException(
                    "simulated load cell stream was stopped and cannot be restarted; open a new one");
        }
        if (reading) {
            return;
        }
        reading = true;
        bench.register(this);
    }

    @Override
    public void stopReading() {
        reading = false;
        stopped = true;
        bench.unregister(this);
        pending.clear();
        queued.set(0);
    }

    @Override
    public List<Measurement> getNextValues() {
        var drained = new ArrayList<Measurement>();
        Measurement measurement;
        while ((measurement = pending.poll()) != null) {
            queued.decrementAndGet();
            drained.add(measurement);
        }
        return drained;
    }

    @Override
    public boolean isReading() {
        return reading;
    }

    @Override
    public StreamFailure lastError() {
        return failure;
    }

    /** Called from the bench tick thread. */
    void offer(Measurement measurement) {
        if (!reading) {
            return;
        }
        pending.add(measurement);
        if (queued.incrementAndGet() > MAX_QUEUED_SAMPLES && pending.poll() != null) {
            queued.decrementAndGet();
        }
    }

    /**
     * Stops the stream the way the real driver does on an error: reading goes false and the cause is
     * available through {@link #lastError()}. The seam step 4's fault injection drives.
     */
    void fail(StreamFailure cause) {
        failure = cause;
        reading = false;
        stopped = true;
        bench.unregister(this);
    }
}
