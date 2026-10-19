package ch.rupfizupfi.deck.device.api;

/**
 * Where the drive is taking its commands from right now, as {@link Drive#readControlSource} reports
 * it. The drive's own state, never the LOC/REM selection a serial master last requested. Since
 * contract 1.3.0.
 */
public enum ControlSource {

    /** The drive's local command source, typically its keypad. */
    LOCAL,

    /** The drive's remote command source, which is where the deck's commands are acted on. */
    REMOTE,

    /** The provider cannot tell. A caller treats it as "not known to be {@link #LOCAL}". */
    UNKNOWN
}
