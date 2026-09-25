package ch.rupfizupfi.deck.device.api;

import java.util.List;

/**
 * A running load cell reader with a queue of samples the caller drains.
 * <p>
 * Six methods rather than the three the reader loop needs, all for the same reason: silence looks
 * identical whatever caused it. Naming the driver's own trip cause takes {@link #isReading()} and
 * {@link #lastError()}; telling a hole the driver punched from one the bus merely caused takes
 * {@link #droppedSampleCount()}. See {@code doc/03-backend/driver-jars.md#dscusbjar--load-cell}.
 */
public interface LoadCellStream {

    void startReading();

    /** Terminal — a stopped stream can never be read again. */
    void stopReading();

    /** Drains whatever has been read since the last call; empty when nothing arrived. */
    List<Measurement> getNextValues();

    boolean isReading();

    /**
     * Why the reader stopped, or null while it is still reading. Always the fault that ENDED the
     * stream, never one the driver absorbed within a retry bound of its own choosing (OQ-74) — for
     * those see {@link #droppedSampleCount()}.
     */
    StreamFailure lastError();

    /**
     * Readings this stream discarded rather than delivered, since it started reading; 0 for a driver
     * that discards none. Monotonic within one reading session.
     * <p>
     * The only observable an absorbed fault leaves behind: the stream keeps reading, so
     * {@link #lastError()} stays null, and the sample timestamps show a hole without saying whether
     * the driver rejected readings or the bus was slow — the difference between a cell going bad
     * and one on a busy port.
     * <p>
     * Defaults to 0 so neither a driver that never drops nor one built against an older contract
     * has to implement it.
     */
    default long droppedSampleCount() {
        return 0;
    }
}
