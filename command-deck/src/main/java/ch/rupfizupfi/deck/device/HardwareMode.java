package ch.rupfizupfi.deck.device;

/**
 * How the deck reaches the machine. Selected by {@code deck.hardware.mode}, default {@link #REAL}.
 * <p>
 * No fallback in either direction: every unsatisfiable combination is a startup failure; see
 * {@link HardwareModeCheck}.
 */
public enum HardwareMode {
    REAL,
    SIMULATED;

    public static final String PROPERTY = "deck.hardware.mode";

    /** Fail-safe: without drivers the deck refuses to start rather than simulate. Dev sets simulated. */
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
