package ch.rupfizupfi.deck.data;

/**
 * Persisted lifecycle status of a test run. Lives in {@code cms} with its column; command-deck
 * depends on cms, never the reverse.
 *
 * <p>Mapped from {@code TestState} by {@code TestResultStatusPersister#mapStatus}. {@code IDLE}
 * leaves the stored status untouched, since writing it would overwrite the verdict of the run
 * that just ended.
 *
 * <p>{@link #IN_PROGRESS} and {@link #INTERRUPTED} are non-terminal: a crash leaves one behind,
 * and {@code StartupRecoveryRunner} sweeps it to {@link #ABORTED} on the next boot.
 *
 * <p>{@link #COMPLETED_WITH_GAPS}: the run survived at least one load-cell loss, so its series has
 * holes. A query that means "clean run" must name {@link #COMPLETED} alone.
 */
public enum RunStatus {
    IN_PROGRESS,
    INTERRUPTED,
    COMPLETED,
    COMPLETED_WITH_GAPS,
    ABORTED,
    FAULT;

    /** False only for the two values a crashed process can leave behind. */
    public boolean isTerminal() {
        return this != IN_PROGRESS && this != INTERRUPTED;
    }
}
