package ch.rupfizupfi.deck.device.simulated;

/**
 * The fault a {@link SimulatedFaultSwitches} switch injects, and the code path it exists to reach.
 * <p>
 * Every one of these paths is unreachable on a healthy bench and has therefore never been observed
 * to work: the detectors and the escalation ladder only run during an incident. That is the whole
 * point of the simulator.
 */
public enum SimulatedFault {

    /** Stream stays open and simply stops delivering → no-data timeout → {@code sensorLost} → safeStop. */
    LOAD_CELL_SILENT("load cell delivers no further samples"),

    /** Bit-identical force, dither suppressed → the frozen-sample detector (100 identical readings). */
    LOAD_CELL_FROZEN("load cell repeats one bit-identical value"),

    /** NaN samples → the 3-of-5 plausibility vote. */
    LOAD_CELL_NAN("load cell reports NaN"),

    /** 10^6 N samples, past the 450 kN bound → the same vote, via the magnitude branch. */
    LOAD_CELL_IMPLAUSIBLE_FORCE("load cell reports an impossible force"),

    /**
     * The driver's reader thread dies and records a cause → {@code LoadCellDevice#getStreamFailure}
     * names it, and the watchdog appends it to the silence trip reason. Distinct from
     * {@link #LOAD_CELL_SILENT}: same escalation, but a diagnosable one.
     */
    LOAD_CELL_STREAM_DEATH("load cell reader thread dies with a driver error"),

    /**
     * Handles opened before the switch stop answering; freshly opened ones work → tier 1 fails,
     * tier 2's re-enumeration succeeds. The isolated tier-2 exercise.
     */
    DRIVE_STALE_HANDLE("existing drive handles stop answering, fresh ones work"),

    /**
     * Every handle refuses, including a freshly opened one → tier 1 fails, tier 2 fails, tier 3
     * escalates to the operator.
     */
    DRIVE_UNRESPONSIVE("no drive handle answers at all"),

    /**
     * Reads and writes succeed but the shaft never decelerates. Deliberately NOT an escalation: the
     * drive is demonstrably answering, so the stop reports {@code coasting()} and the ladder stays
     * put — re-enumerating a working handle would only delay a stop that is already commanded.
     */
    DRIVE_MOTOR_NEVER_SLOWS("drive accepts the stop but the motor keeps turning"),

    /** {@code close()} throws → {@code CFW11Device#closeDriveHandle}'s best-effort path. */
    DRIVE_CLOSE_THROWS("closing a drive handle throws");

    private final String description;

    SimulatedFault(String description) {
        this.description = description;
    }

    public String description() {
        return description;
    }
}
