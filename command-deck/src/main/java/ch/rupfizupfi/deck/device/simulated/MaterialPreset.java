package ch.rupfizupfi.deck.device.simulated;

import java.util.Locale;

/**
 * Stiffness / break-force presets keyed by the names seeded into the `material` table.
 * <p>
 * <b>Invented, uncalibrated.</b> Only the ordering between them is defensible — steel is stiffer
 * than hemp — and even that is intuition, not measurement. Selected with
 * {@code deck.simulated.material}; unset means the flat defaults in
 * {@link SimulatedBenchProperties} apply.
 */
enum MaterialPreset {
    MULTI("multi", 2000, 25_000),
    POLYAMID("Polyamid", 900, 15_000),
    POLYESTER("Polyester", 1400, 18_000),
    STEEL("Steel", 8000, 60_000),
    ALUMINIUM("Aluminium", 5000, 30_000),
    DYNEEMA("Dyneema (UHMW-PE)", 3000, 40_000),
    KEVLAR("Kevlar", 2500, 30_000),
    HEMP("Hemp", 600, 8_000);

    private final String materialName;
    private final double stiffnessNewtonPerMm;
    private final double breakForceNewton;

    MaterialPreset(String materialName, double stiffnessNewtonPerMm, double breakForceNewton) {
        this.materialName = materialName;
        this.stiffnessNewtonPerMm = stiffnessNewtonPerMm;
        this.breakForceNewton = breakForceNewton;
    }

    double stiffnessNewtonPerMm() {
        return stiffnessNewtonPerMm;
    }

    double breakForceNewton() {
        return breakForceNewton;
    }

    String materialName() {
        return materialName;
    }

    /** Matches on the seeded material name or the enum constant; null when nothing matches. */
    static MaterialPreset find(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        String wanted = name.trim().toLowerCase(Locale.ROOT);
        for (MaterialPreset preset : values()) {
            if (preset.materialName.toLowerCase(Locale.ROOT).equals(wanted)
                    || preset.name().toLowerCase(Locale.ROOT).equals(wanted)) {
                return preset;
            }
        }
        return null;
    }
}
