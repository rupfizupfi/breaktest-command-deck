package ch.rupfizupfi.deck.testrunner.measure;

import ch.rupfizupfi.deck.device.api.Measurement;
import ch.rupfizupfi.deck.testrunner.run.RunEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.Writer;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Owns one run's force CSV on a thread of its own, so the measurement loop never waits on a disk
 * (A-07, OQ-64 step 9): the loop {@link #offer}s each batch and goes back to its detectors.
 * <p>
 * A write failure, or a batch refused because the queue is full, is a persistence fault: the
 * writer raises {@link RunEvent.PersistenceFailed} once and the run loop ends the run {@code FAULT}.
 * A lost trace is not a safety event, so nothing here touches the motor.
 * <p>
 * The row is {@code timestamp,force} through a {@link BufferedWriter}, byte for byte what the
 * measurement loop wrote inline before. One writer for the whole run: the file stays open across a
 * hold, so a resumed run has one continuous CSV.
 */
public final class TraceWriter {
    private static final Logger logger = LoggerFactory.getLogger(TraceWriter.class);

    /**
     * Batches the writer may fall behind by. The driver delivers about fifty a second, so this is
     * about twenty seconds of a stalled disk, the same backlog the measurement loop's own inbound
     * queue tolerates; past it a batch is refused and the run faults.
     */
    public static final int QUEUE_CAPACITY_BATCHES = 1024;
    /** Longest the loss and ceiling paths wait for their tail to reach the file. */
    public static final long FLUSH_BOUND_MS = 500;
    /** Longest {@link #close()} waits for the queue to drain before it gives up on the tail. */
    static final long CLOSE_BOUND_MS = 5000;

    /** A flush request; completed by the writer thread once every batch queued before it is flushed. */
    private record Flush(CountDownLatch done) {
    }

    private static final Object CLOSE = new Object();

    private final BufferedWriter out;
    private final String path;
    private final RunEventSink events;
    private final BlockingQueue<Object> queue = new ArrayBlockingQueue<>(QUEUE_CAPACITY_BATCHES);
    private final Thread thread;
    /** Raised once per run, by whichever thread sees the first failure. */
    private final AtomicBoolean failed = new AtomicBoolean();
    /** Samples refused by a full queue or discarded after a write failure. */
    private final AtomicLong lostSamples = new AtomicLong();
    private volatile boolean closed = false;

    /**
     * Starts the writer thread on {@code out}.
     *
     * @param path       the CSV's path, for the log and the fault detail only
     * @param events     where the {@link RunEvent.PersistenceFailed} goes; must never block
     * @param threadName the writer thread's name
     */
    public TraceWriter(Writer out, String path, RunEventSink events, String threadName) {
        this.out = new BufferedWriter(out);
        this.path = path;
        this.events = events;
        this.thread = new Thread(this::run, threadName);
        // A disk that never returns must not keep the JVM alive.
        this.thread.setDaemon(true);
        this.thread.start();
    }

    /**
     * Hands a batch to the writer thread. Never blocks. The list must not be changed afterwards.
     *
     * @return false when the batch was refused; the first refusal raises the persistence fault
     */
    public boolean offer(List<Measurement> batch) {
        if (batch.isEmpty()) {
            return true;
        }
        if (closed) {
            // The run is over; nothing that arrives now is part of its trace.
            return false;
        }
        if (!failed.get() && queue.offer(batch)) {
            return true;
        }
        long lost = lostSamples.addAndGet(batch.size());
        fail("the force trace writer is " + QUEUE_CAPACITY_BATCHES + " batches behind the load cell, "
                + lost + " sample(s) lost", null);
        return false;
    }

    /**
     * Waits until every batch offered before this call is written and flushed, at most
     * {@code boundMillis}. For the loss and ceiling paths, so the incident tail is on disk before
     * the run moves on; a stalled disk delays them by the bound, never longer.
     *
     * @return false when the bound ran out or the writer can no longer write
     */
    public boolean flush(long boundMillis) {
        if (closed || failed.get()) {
            return false;
        }
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(boundMillis);
        boolean interrupted = Thread.interrupted();
        try {
            Flush marker = new Flush(new CountDownLatch(1));
            if (!queue.offer(marker, boundMillis, TimeUnit.MILLISECONDS)) {
                return false;
            }
            return marker.done().await(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
        } catch (InterruptedException e) {
            interrupted = true;
            return false;
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * Writes the queued tail, flushes and closes the file, bounded by {@link #CLOSE_BOUND_MS}.
     * Idempotent. Safe on an interrupted thread: the measurement loop closes the trace in its
     * finally, after its own interrupt.
     */
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        boolean interrupted = Thread.interrupted();
        try {
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(CLOSE_BOUND_MS);
            if (queue.offer(CLOSE, CLOSE_BOUND_MS, TimeUnit.MILLISECONDS)) {
                thread.join(Math.max(1, TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime())));
            }
            if (thread.isAlive()) {
                logger.error("The force trace writer did not finish {} within {} ms; its tail may be missing",
                        path, CLOSE_BOUND_MS);
                thread.interrupt();
            }
        } catch (InterruptedException e) {
            interrupted = true;
            thread.interrupt();
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
        long lost = lostSamples.get();
        if (lost > 0) {
            logger.error("The force CSV {} is missing {} sample(s)", path, lost);
        }
    }

    /** Samples lost to a full queue or a failed write; for the tests and the close log. */
    public long lostSamples() {
        return lostSamples.get();
    }

    /** The writer thread; package-private for tests. */
    Thread writerThread() {
        return thread;
    }

    private void run() {
        try {
            while (true) {
                Object item = queue.take();
                if (item == CLOSE) {
                    break;
                }
                if (item instanceof Flush flush) {
                    try {
                        if (!failed.get()) {
                            out.flush();
                        }
                    } catch (IOException e) {
                        fail("could not flush the force CSV " + path + ": " + e.getMessage(), e);
                    } finally {
                        flush.done().countDown();
                    }
                    continue;
                }
                @SuppressWarnings("unchecked")
                List<Measurement> batch = (List<Measurement>) item;
                write(batch);
            }
        } catch (InterruptedException e) {
            // Only close() interrupts, once its bound ran out; what is still queued is lost.
            Thread.currentThread().interrupt();
        } finally {
            try {
                out.close();
            } catch (IOException e) {
                // Close flushes the tail first: a failure here lost samples the run already measured.
                fail("could not close the force CSV " + path + ": " + e.getMessage(), e);
            }
        }
    }

    private void write(List<Measurement> batch) {
        if (failed.get()) {
            lostSamples.addAndGet(batch.size());
            return;
        }
        try {
            for (Measurement measurement : batch) {
                // The one place the CSV row format is written, so hold and gate rows are identical
                // to normal ones.
                out.write(measurement.timestamp() + "," + measurement.force());
                out.newLine();
            }
        } catch (IOException | RuntimeException e) {
            // RuntimeException too: a writer thread that died silently would lose the rest unseen.
            lostSamples.addAndGet(batch.size());
            fail("could not write the force CSV " + path + ": " + e.getMessage(), e);
        }
    }

    /** The first failure of the run raises the fault; every later one is only counted. */
    private void fail(String detail, Throwable cause) {
        if (!failed.compareAndSet(false, true)) {
            return;
        }
        logger.error("Force trace persistence failed: {}", detail, cause);
        try {
            events.accept(new RunEvent.PersistenceFailed(detail));
        } catch (Exception e) {
            logger.error("Could not tell the run loop about a persistence failure", e);
        }
    }
}
