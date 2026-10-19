package ch.rupfizupfi.deck.testrunner.measure;

import ch.rupfizupfi.deck.device.api.Measurement;
import ch.rupfizupfi.deck.testrunner.run.RunEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The force CSV's writer thread (OQ-64 step 9, A-07): the row stays byte-identical, a stalled disk
 * never blocks {@link TraceWriter#offer}, and losing samples is one persistence fault.
 */
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class TraceWriterTest {

    @TempDir
    Path tempDir;

    private final List<RunEvent> events = Collections.synchronizedList(new ArrayList<>());
    private final List<TraceWriter> opened = new ArrayList<>();
    private final StallingWriter stalling = new StallingWriter();

    @AfterEach
    void closeEverything() {
        stalling.release();
        for (TraceWriter trace : opened) {
            trace.close();
            assertThat(trace.writerThread().isAlive()).as("writer thread ended").isFalse();
        }
    }

    private TraceWriter open(Writer out) {
        TraceWriter trace = new TraceWriter(out, "run_force.csv", events::add, "trace-writer-test");
        opened.add(trace);
        return trace;
    }

    /** A disk that blocks inside its first write until released, and reports when it is stuck. */
    private static final class StallingWriter extends Writer {
        final CountDownLatch stuck = new CountDownLatch(1);
        private final CountDownLatch released = new CountDownLatch(1);
        final StringBuilder written = new StringBuilder();

        @Override
        public void write(char[] buffer, int offset, int length) throws IOException {
            stuck.countDown();
            try {
                released.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("interrupted", e);
            }
            synchronized (written) {
                written.append(buffer, offset, length);
            }
        }

        @Override
        public void flush() {
            stuck.countDown();
            try {
                released.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        @Override
        public void close() {
        }

        void release() {
            released.countDown();
        }
    }

    /** A disk that refuses every write. */
    private static final class FailingWriter extends Writer {
        @Override
        public void write(char[] buffer, int offset, int length) throws IOException {
            throw new IOException("disk full");
        }

        @Override
        public void flush() throws IOException {
            throw new IOException("disk full");
        }

        @Override
        public void close() {
        }
    }

    private static List<Measurement> samples(int count, long firstTimestamp) {
        List<Measurement> batch = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            batch.add(new Measurement(10f + i * 0.001f, firstTimestamp + i));
        }
        return batch;
    }

    /** Enough rows to overflow the BufferedWriter's 8 KiB buffer, so the stalled disk is reached. */
    private static List<Measurement> pastTheBuffer() {
        return samples(1000, 1_760_000_000_000L);
    }

    private static List<Measurement> goldenSamples() {
        float[] forces = {0f, -0f, 12.5f, -150.25f, 1e-5f, 123456.79f, Float.NaN, Float.POSITIVE_INFINITY,
                Float.NEGATIVE_INFINITY, Float.MAX_VALUE, Float.MIN_VALUE, 99.99f};
        List<Measurement> samples = new ArrayList<>();
        for (int i = 0; i < forces.length; i++) {
            samples.add(new Measurement(forces[i], 1_760_000_000_000L + i));
        }
        return samples;
    }

    /** The golden's rows as {@code BufferedWriter#newLine} ends them on this platform. */
    private static byte[] golden() throws IOException {
        try (InputStream in = TraceWriterTest.class.getResourceAsStream("trace-writer-golden.csv")) {
            assertThat(in).as("golden resource").isNotNull();
            String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            StringBuilder expected = new StringBuilder();
            for (String line : text.split("\\R")) {
                if (!line.isEmpty()) {
                    expected.append(line).append(System.lineSeparator());
                }
            }
            return expected.toString().getBytes(StandardCharsets.UTF_8);
        }
    }

    @Test
    void theFileIsByteIdenticalToTheGoldenAndKeepsOrderAcrossBatches() throws IOException {
        Path csv = tempDir.resolve("run_force.csv");
        TraceWriter trace = open(new FileWriter(csv.toString()));
        List<Measurement> samples = goldenSamples();
        // Uneven batches, so a reorder at a batch boundary would show.
        trace.offer(samples.subList(0, 1));
        trace.offer(samples.subList(1, 5));
        trace.offer(List.of());
        trace.offer(samples.subList(5, 12));
        trace.close();

        assertThat(Files.readAllBytes(csv)).isEqualTo(golden());
        assertThat(events).isEmpty();
        assertThat(trace.lostSamples()).isZero();
    }

    @Test
    void closeWritesTheQueuedTail() {
        StringWriter out = new StringWriter();
        TraceWriter trace = open(out);
        for (int i = 0; i < 50; i++) {
            trace.offer(samples(10, i * 10L));
        }
        trace.close();

        assertThat(out.toString().split("\\R")).hasSize(500);
        assertThat(out.toString()).startsWith("0,10.0" + System.lineSeparator())
                .endsWith("499," + (10f + 9 * 0.001f) + System.lineSeparator());
    }

    @Test
    void offerNeverWaitsOnAStalledDisk() throws InterruptedException {
        TraceWriter trace = open(stalling);
        trace.offer(pastTheBuffer());
        assertThat(stalling.stuck.await(5, TimeUnit.SECONDS)).as("writer thread stuck in the disk").isTrue();

        long slowest = 0;
        for (int i = 0; i < 100; i++) {
            long before = System.nanoTime();
            assertThat(trace.offer(samples(50, i * 50L))).isTrue();
            slowest = Math.max(slowest, System.nanoTime() - before);
        }

        // Microseconds in practice; the bound only absorbs a descheduled test thread.
        assertThat(TimeUnit.NANOSECONDS.toMillis(slowest)).as("slowest offer, ms").isLessThan(50);
        assertThat(events).isEmpty();
    }

    @Test
    void aFullQueueRaisesOnePersistenceFailureWithTheLostCount() throws InterruptedException {
        TraceWriter trace = open(stalling);
        trace.offer(pastTheBuffer());
        assertThat(stalling.stuck.await(5, TimeUnit.SECONDS)).isTrue();
        // The writer holds the first batch, so the queue is empty and takes exactly its capacity.
        for (int i = 0; i < TraceWriter.QUEUE_CAPACITY_BATCHES; i++) {
            assertThat(trace.offer(samples(1, i))).as("batch %d", i).isTrue();
        }

        assertThat(trace.offer(samples(7, 0))).isFalse();
        assertThat(trace.offer(samples(3, 0))).isFalse();

        assertThat(events).hasSize(1);
        assertThat(events.getFirst()).isInstanceOfSatisfying(RunEvent.PersistenceFailed.class,
                failed -> assertThat(failed.detail()).contains("7 sample(s) lost"));
        assertThat(trace.lostSamples()).isEqualTo(10);
    }

    @Test
    void aWriteFailureRaisesOnePersistenceFailureAndLaterBatchesAreCountedLost() {
        TraceWriter trace = open(new FailingWriter());
        trace.offer(pastTheBuffer());
        org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(5)).until(() -> !events.isEmpty());

        trace.offer(samples(5, 0));
        trace.close();

        assertThat(events).hasSize(1);
        assertThat(events.getFirst()).isInstanceOfSatisfying(RunEvent.PersistenceFailed.class,
                failed -> assertThat(failed.detail()).contains("disk full"));
        assertThat(trace.lostSamples()).isEqualTo(1005);
    }

    @Test
    void flushReturnsInsideItsBoundOnAStalledDisk() throws InterruptedException {
        TraceWriter trace = open(stalling);
        trace.offer(pastTheBuffer());
        assertThat(stalling.stuck.await(5, TimeUnit.SECONDS)).isTrue();

        long before = System.nanoTime();
        boolean flushed = trace.flush(200);
        long tookMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - before);

        assertThat(flushed).isFalse();
        assertThat(tookMs).isBetween(150L, 1_000L);
        // A slow disk is not yet a lost sample.
        assertThat(events).isEmpty();
    }

    @Test
    void flushPutsEverythingOfferedBeforeItOnDisk() throws IOException {
        Path csv = tempDir.resolve("run_force.csv");
        TraceWriter trace = open(new FileWriter(csv.toString()));
        trace.offer(samples(3, 0));

        assertThat(trace.flush(TraceWriter.FLUSH_BOUND_MS)).isTrue();

        // Read while the file is still open: the flush, not the close, put the rows there.
        assertThat(Files.readAllLines(csv)).hasSize(3);
    }

    @Test
    void closeIsSafeOnAnInterruptedThreadAndKeepsTheInterrupt() {
        StringWriter out = new StringWriter();
        TraceWriter trace = open(out);
        trace.offer(samples(4, 0));

        Thread.currentThread().interrupt();
        try {
            trace.close();
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
        assertThat(out.toString().split("\\R")).hasSize(4);
    }

    @Test
    void aBatchOfferedAfterCloseIsRefusedWithoutAFault() {
        TraceWriter trace = open(new StringWriter());
        trace.close();

        assertThat(trace.offer(samples(2, 0))).isFalse();
        assertThat(events).isEmpty();
    }
}
