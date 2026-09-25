package ch.rupfizupfi.deck.data;

/**
 * Persisted lifecycle status of a test run. Lives in {@code cms} because the column does — the
 * module dependency runs command-deck → cms and never back — so this enum deliberately names the
 * command-deck state machine only in prose.
 *
 * <p>Written from {@code TestState} by {@code TestResultStatusPersister#mapStatus}, which owns the
 * mapping. The one part of it that is not mechanical: {@code IDLE} leaves the stored status
 * untouched — a machine sits in it before and after a run, so writing it would overwrite the
 * verdict of the run that just ended.
 *
 * <p>{@link #IN_PROGRESS} and {@link #INTERRUPTED} are the two non-terminal values, and they exist
 * so that a crash leaves a findable orphan row instead of nothing: whichever one the process died
 * in is still in the database on the next boot, where {@code StartupRecoveryRunner} sweeps it to
 * {@link #ABORTED}.
 *
 * <p>{@link #COMPLETED_WITH_GAPS} is a separate value rather than a flag alongside
 * {@link #COMPLETED} precisely so that it cannot be read as a clean run by accident. The run
 * survived at least one load-cell loss, so its measurement series has holes and its result is not
 * comparable with one taken under continuous measurement. Any query that means "clean run" must
 * name {@link #COMPLETED} alone.
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
