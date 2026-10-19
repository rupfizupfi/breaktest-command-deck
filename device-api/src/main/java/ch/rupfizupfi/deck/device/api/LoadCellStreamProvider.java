package ch.rupfizupfi.deck.device.api;

/**
 * Opens a load cell stream. A factory because a stopped stream cannot restart, so every reconnect
 * needs a new instance; see {@code doc/dev/backend/safety-and-recovery.md}.
 */
public interface LoadCellStreamProvider {

    /** Returns a stream that is not yet reading; the caller calls {@code startReading()}. */
    LoadCellStream open();
}
