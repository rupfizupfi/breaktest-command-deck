package ch.rupfizupfi.deck.device.simulated;

/**
 * The fault a {@link SimulatedFaultSwitches} switch injects; each trips a path unreachable on a
 * healthy bench. What each trips and the observed outcome:
 * {@code doc/dev/backend/fault-injection.md}.
 */
public enum SimulatedFault {

    /** → no-data timeout. */
    LOAD_CELL_SILENT("load cell delivers no further samples"),

    /** → frozen-sample detector. */
    LOAD_CELL_FROZEN("load cell repeats one bit-identical value"),

    /** → plausibility vote, NaN branch. */
    LOAD_CELL_NAN("load cell reports NaN"),

    /** → no trip while the hole stays shorter than the no-data timeout; the count is the only trace. */
    LOAD_CELL_DROPPED_SAMPLES("load cell driver discards readings without dying"),

    /** → plausibility vote, magnitude branch. */
    LOAD_CELL_IMPLAUSIBLE_FORCE("load cell reports an impossible force"),

    /** → no-data timeout, but with a driver-named cause. */
    LOAD_CELL_STREAM_DEATH("load cell reader thread dies with a driver error"),

    /** → the resume drift gate's REJECT branch, the only branch a healthy reconnect never reaches. */
    LOAD_CELL_RECONNECT_GARBAGE("a reconnected load cell reads a large constant offset at first"),

    /** → {@code Device#reset}'s "openConnection threw" path, and the reconnect backoff that retries it. */
    LOAD_CELL_STREAM_OPEN_FAILS("opening a load cell stream throws"),

    /** → the max-losses-per-run cap and the {@code RESUMING -> SENSOR_LOST} edge. */
    LOAD_CELL_FLAPPING("the load cell stream dies again shortly after every reconnect"),

    /** → tier 1 fails, tier 2 re-enumerates and succeeds. */
    DRIVE_STALE_HANDLE("existing drive handles stop answering, fresh ones work"),

    /** → both software tiers fail, tier 3 escalates to the operator. */
    DRIVE_UNRESPONSIVE("no drive handle answers at all"),

    /** → {@code coasting()}, no escalation: the drive is answering. */
    DRIVE_MOTOR_NEVER_SLOWS("drive accepts the stop but the motor keeps turning"),

    /** → {@code FrequencyInverterDevice#closeDriveHandle}'s best-effort path. */
    DRIVE_CLOSE_THROWS("closing a drive handle throws"),

    /**
     * → {@code DriveDeadlineException} for every call behind it, and the drive's own link-loss stop:
     * one transaction hangs before it reaches the drive, starving the watchdog. One-shot: the
     * transaction that takes it disarms it.
     */
    DRIVE_TRANSACTION_WEDGED("the next drive transaction hangs for deck.simulated.wedge-millis");

    private final String description;

    SimulatedFault(String description) {
        this.description = description;
    }

    public String description() {
        return description;
    }
}
