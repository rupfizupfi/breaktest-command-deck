package ch.rupfizupfi.deck.device;

/**
 * How the deck reaches the machine. Selected by {@code deck.hardware.mode}, default {@link #REAL}.
 * <p>
 * There is deliberately no fallback in either direction: absent hardware must never silently select
 * a simulator, and a simulator must never be mistaken for a bench. Every unsatisfiable combination
 * is a startup failure — see {@link HardwareModeCheck}.
 */
public enum HardwareMode {
    REAL,
    SIMULATED;

    public static final String PROPERTY = "deck.hardware.mode";

    /** Default while no simulator exists; the dev profile flips it only once one does. */
    public static final HardwareMode DEFAULT = REAL;

    static HardwareMode parse(String value) {
        for (HardwareMode mode : values()) {
            if (mode.name().equalsIgnoreCase(value)) {
                return mode;
            }
        }
        return null;
    }

    String propertyValue() {
        return name().toLowerCase();
    }
}
