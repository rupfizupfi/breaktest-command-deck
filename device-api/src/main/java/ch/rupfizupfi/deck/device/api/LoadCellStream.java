package ch.rupfizupfi.deck.device.api;

import java.util.List;

/**
 * A running load cell reader with a queue of samples the caller drains.
 * <p>
 * {@link #isReading()}, {@link #lastError()} and {@link #droppedSampleCount()} exist to tell the
 * causes of silence apart. See {@code doc/dev/backend/driver-jars.md#dscusbjar--load-cell}.
 */
public interface LoadCellStream {

    /**
     * Starts the reader. Once per stream: a second call, while reading or after the stream ended,
     * is refused with {@link IllegalStateException} — a provider opens a new stream instead.
     * {@code LoadCellStreamContractTest} in this build's test fixtures holds every provider to the
     * behaviour documented here.
     */
    void startReading();

    /** Terminal — a stopped stream can never be read again. */
    void stopReading();

    /** Drains whatever has been read since the last call; empty when nothing arrived. */
    List<Measurement> getNextValues();

    boolean isReading();

    /**
     * Why the reader stopped, or null while it is still reading. Only the fault that ended the
     * stream, never one the driver absorbed (OQ-74); for those see {@link #droppedSampleCount()}.
     */
    StreamFailure lastError();

    /**
     * Readings this stream discarded rather than delivered, since it started reading; 0 for a driver
     * that discards none. Monotonic within one reading session.
     * <p>
     * The only trace an absorbed fault leaves: it tells a driver rejecting readings (a cell going
     * bad) from a slow bus, which the timestamps alone cannot.
     */
    default long droppedSampleCount() {
        return 0;
    }
}
