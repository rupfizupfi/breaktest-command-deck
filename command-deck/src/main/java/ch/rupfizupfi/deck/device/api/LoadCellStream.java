package ch.rupfizupfi.deck.device.api;

import java.util.List;

/**
 * A running load cell reader with a queue of samples the caller drains.
 * <p>
 * Five methods rather than the three the reader loop needs: naming the driver's own trip cause
 * requires {@link #isReading()} and {@link #lastError()} as well, because silence looks identical
 * whatever caused it. See {@code doc/03-backend/driver-jars.md#dscusbjar--load-cell}.
 */
public interface LoadCellStream {

    void startReading();

    /** Terminal — a stopped stream can never be read again. */
    void stopReading();

    /** Drains whatever has been read since the last call; empty when nothing arrived. */
    List<Measurement> getNextValues();

    boolean isReading();

    /**
     * Why the reader stopped, or null while it is still reading. A non-null value alongside
     * {@link #isReading()} false is the driver naming its own trip cause; the stream is dead either
     * way — one non-finite sample ends it (OQ-74).
     */
    StreamFailure lastError();
}
