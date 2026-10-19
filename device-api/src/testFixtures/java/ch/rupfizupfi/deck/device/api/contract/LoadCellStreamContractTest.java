package ch.rupfizupfi.deck.device.api.contract;

import ch.rupfizupfi.deck.device.api.LoadCellStream;
import ch.rupfizupfi.deck.device.api.Measurement;
import ch.rupfizupfi.deck.device.api.StreamFailure;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.fail;
import static org.assertj.core.api.Assumptions.assumeThat;

/**
 * The documented behaviour of {@link LoadCellStream}, which every provider - the deck's simulator
 * and the load-cell driver - extends with a {@link Fixture} of its own. Since contract 1.4.0.
 * <p>
 * A stream may read on its own thread, so every observation of something the source did is awaited
 * up to {@link #AWAIT}, never assumed to be immediate. What a call does to the stream's own state
 * ({@code isReading} after {@code startReading} or {@code stopReading}) is checked at once.
 */
public abstract class LoadCellStreamContractTest {

    /** Bounds every wait for the reader; a healthy fixture answers within milliseconds. */
    protected static final Duration AWAIT = Duration.ofSeconds(2);

    /** A stream under test and the sensor behind it. */
    public interface Fixture extends AutoCloseable {

        /** A stream as the provider's {@code open()} returns it: not yet reading. */
        LoadCellStream open();

        /** The sensor produces these forces, in order. Nothing is produced unless fed. */
        void feed(float... forces);

        /**
         * The sensor produces {@code count} readings the driver must discard rather than deliver -
         * a fault it absorbs and reads on past.
         */
        void dropReadings(int count);

        /** The sensor fails in a way that ends the stream. */
        void injectFault();

        /** True only when a real cell stands behind the stream; gates the bench cases. */
        default boolean liveSensor() {
            return false;
        }

        @Override
        default void close() {
        }
    }

    protected abstract Fixture createFixture();

    protected Fixture fixture;
    protected LoadCellStream stream;

    @BeforeEach
    void openStream() {
        fixture = createFixture();
        stream = fixture.open();
    }

    @AfterEach
    void stopStream() throws Exception {
        try {
            stream.stopReading();
        } catch (RuntimeException alreadyStopped) {
            // Some cases end the stream themselves.
        } finally {
            fixture.close();
        }
    }

    @Test
    void aFreshStreamIsNotReading() {
        assertThat(stream.isReading()).isFalse();
        assertThat(stream.lastError()).isNull();
        assertThat(stream.droppedSampleCount()).isZero();
        assertThat(stream.getNextValues()).isEmpty();
    }

    @Test
    void startReadingStartsTheReader() {
        stream.startReading();

        assertThat(stream.isReading()).isTrue();
        assertThat(stream.lastError()).isNull();
        assertThat(stream.droppedSampleCount()).isZero();
    }

    @Test
    void aSecondStartWhileReadingIsRefused() {
        stream.startReading();

        assertThatThrownBy(stream::startReading).isInstanceOf(IllegalStateException.class);
        assertThat(stream.isReading()).as("a refused start leaves the reader running").isTrue();
    }

    @Test
    void getNextValuesIsEmptyUntilTheSensorProducesThenDrainsInOrder() {
        stream.startReading();
        assertThat(stream.getNextValues()).isEmpty();

        fixture.feed(1.5f, 2.5f, 3.5f);

        List<Float> delivered = new ArrayList<>();
        await("three readings", () -> {
            stream.getNextValues().forEach(m -> delivered.add(m.force()));
            return delivered.size() >= 3;
        });
        assertThat(delivered).containsExactly(1.5f, 2.5f, 3.5f);
        assertThat(stream.getNextValues()).as("a drained queue").isEmpty();
    }

    @Test
    void stopReadingIsTerminal() {
        stream.startReading();

        stream.stopReading();

        assertThat(stream.isReading()).isFalse();
        assertThatThrownBy(stream::startReading).isInstanceOf(IllegalStateException.class);
        assertThat(stream.isReading()).isFalse();
    }

    /** {@code LoadCellDevice} releases a stream whose start failed, so stop must not need a start. */
    @Test
    void aStreamNeverStartedCanBeStoppedAndThenNeverStarts() {
        stream.stopReading();

        assertThat(stream.isReading()).isFalse();
        assertThatThrownBy(stream::startReading).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void lastErrorIsNullWhileReadingAndNamesTheFaultThatEndedTheStream() {
        stream.startReading();
        fixture.feed(1f);
        await("the first reading", () -> !stream.getNextValues().isEmpty());
        assertThat(stream.lastError()).as("while reading").isNull();

        fixture.injectFault();

        await("the reader to end on the fault", () -> !stream.isReading());
        StreamFailure failure = stream.lastError();
        assertThat(failure).as("after a terminal fault").isNotNull();
        assertThat(failure.failureType()).as("failureType is never null").isNotNull();
        assertThatThrownBy(stream::startReading).isInstanceOf(IllegalStateException.class);
        assertThat(stream.lastError()).as("a refused restart keeps the cause").isNotNull();
    }

    @Test
    void droppedSampleCountRisesMonotonicallyAndTheStreamReadsOn() {
        assertThat(stream.droppedSampleCount()).as("before start").isZero();
        stream.startReading();
        assertThat(stream.droppedSampleCount()).as("at start").isZero();

        fixture.dropReadings(3);
        long[] last = {0};
        await("three discarded readings to be counted", () -> {
            long now = stream.droppedSampleCount();
            assertThat(now).as("droppedSampleCount never falls").isGreaterThanOrEqualTo(last[0]);
            last[0] = now;
            return now >= 3;
        });

        fixture.feed(7f);
        List<Measurement> delivered = new ArrayList<>();
        await("a reading after the drops", () -> {
            delivered.addAll(stream.getNextValues());
            return !delivered.isEmpty();
        });
        assertThat(delivered).extracting(Measurement::force).contains(7f);
        assertThat(stream.isReading()).as("an absorbed fault does not end the stream").isTrue();
        assertThat(stream.lastError()).as("an absorbed fault is not lastError").isNull();
        assertThat(stream.droppedSampleCount()).isGreaterThanOrEqualTo(last[0]);
    }

    /** A wired cell delivers on its own, with epoch-millis timestamps. */
    @Test
    @Tag("bench")
    void aLiveCellDeliversTimestampedReadings() {
        assumeThat(fixture.liveSensor()).as("a real load cell").isTrue();
        long before = System.currentTimeMillis();
        stream.startReading();

        List<Measurement> delivered = new ArrayList<>();
        await("a live reading", () -> {
            delivered.addAll(stream.getNextValues());
            return !delivered.isEmpty();
        });
        assertThat(delivered.getFirst().timestamp()).isGreaterThanOrEqualTo(before);
    }

    protected static void await(String what, BooleanSupplier condition) {
        long deadline = System.nanoTime() + AWAIT.toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(2);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                fail("interrupted waiting for " + what);
            }
        }
        if (!condition.getAsBoolean()) {
            fail("timed out after " + AWAIT.toMillis() + " ms waiting for " + what);
        }
    }
}
