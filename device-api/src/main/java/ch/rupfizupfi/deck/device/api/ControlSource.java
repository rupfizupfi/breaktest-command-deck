package ch.rupfizupfi.deck.device.api;

/**
 * Where the drive is taking its commands from right now, as {@link Drive#readControlSource} reports
 * it. The drive's own state, never the LOC/REM selection a serial master last requested. Since
 * contract 1.3.0.
 * <p>
 * Which device each source is depends on the drive's configuration (CFW11 P0220 and its command
 * source parameters), not on the enum: on this bench LOCAL is the serial/USB interface, i.e. the
 * deck, and REMOTE is the push-button control board.
 */
public enum ControlSource {

    /** The drive's local command source; on this bench the deck over USB. */
    LOCAL,

    /** The drive's remote command source; on this bench the push-button control board. */
    REMOTE,

    /** The provider cannot tell. A caller treats it as "not known to be {@link #REMOTE}". */
    UNKNOWN
}
