package ch.rupfizupfi.deck.device.api;

/**
 * Opens a load cell stream. A factory and not a singleton because a stopped stream can never be
 * restarted, so every reconnect needs a new instance — the constraint the
 * {@code doc/06-feature-work/testrunner-safety/loadcell-recovery-design.md} recovery path is built
 * around.
 */
public interface LoadCellStreamProvider {

    /** Returns a stream that is not yet reading; the caller calls {@code startReading()}. */
    LoadCellStream open();
}
