package ch.rupfizupfi.deck.device.simulated;

import java.util.Arrays;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * Specimen behaviour measured off the bench's own destructive runs, selected with
 * {@code deck.simulated.profile}.
 * <p>
 * Every number is a median over real force traces (the run directories under
 * bench's {@code ~/breaktester} result directory, derived by {@code script/derive-sim-profiles.py}). Extension is the
 * simulated crosshead travel, {@code speed / 60} mm per second, so all runs are assumed to have used
 * speed 350 — the only value the result export records. Method, per-profile run lists and what the
 * data cannot tell apart: {@code doc/dev/backend/specimen-profiles.md}.
 * <p>
 * Only the two polyamide profiles carry a material: the export names theirs. The others are
 * behaviour classes of unlabelled runs, so no seeded material maps to them.
 */
enum SpecimenProfile {
    /** 147 runs that need 35–110 s to break; includes the labelled polyamide webbing. The default. */
    COMPLIANT("compliant", null, 1854, 24_643, 0.052, 378, 0.128,
            0.066, 0.120, 0.176, 0.248, 0.330, 0.438, 0.564, 0.714, 0.865),
    /** 100 runs that reach 25–48 kN inside 35 s. */
    STIFF("stiff", null, 2139, 34_217, 0.041, 105, 0.150,
            0.047, 0.105, 0.173, 0.256, 0.358, 0.479, 0.606, 0.729, 0.865),
    /** 26 runs about as stiff as {@link #STIFF} that break near 20 kN; only three batches. */
    STIFF_LOW_STRENGTH("stiff-low-strength", null, 1876, 20_201, 0.048, 53, 0.200,
            0.051, 0.127, 0.200, 0.277, 0.371, 0.484, 0.601, 0.739, 0.869),
    /** Runs 113–119 and 143–146: Spider Nylove and Slacktivity Pinktube polyamide webbing, intact. */
    POLYAMIDE_WEBBING("polyamide-webbing", "Polyamid", 1654, 19_770, 0.052, 298, 0.142,
            0.099, 0.148, 0.204, 0.274, 0.356, 0.449, 0.569, 0.689, 0.835),
    /** Runs 98–109 and 140–142: UV-exposed or damaged polyamide webbing from the same export. */
    POLYAMIDE_WEBBING_DEGRADED("polyamide-webbing-degraded", null, 1840, 6658, 0.062, 117, 0.297,
            0.198, 0.262, 0.318, 0.442, 0.489, 0.612, 0.711, 0.778, 0.857);

    static final SpecimenProfile DEFAULT = COMPLIANT;

    private final String key;
    private final String materialName;
    private final double pretensionNewton;
    private final double breakForceNewton;
    private final double breakForceSpread;
    private final double breakExtensionMm;
    private final double breakExtensionSpread;
    /** Normalised loading curve: (F - pretension) / (break - pretension) at extension 0, 0.1, ... 1.0. */
    private final double[] shape;

    /**
     * @param breakForceSpread     coefficient of variation of the break force inside one batch of
     *                             consecutive runs on one product — not across the whole profile
     * @param breakExtensionSpread the same for extension at break; also absorbs specimen length
     * @param interiorShape        the nine interior knots of {@link #shape}, x = 0.1 to 0.9
     */
    SpecimenProfile(String key, String materialName, double pretensionNewton, double breakForceNewton,
                    double breakForceSpread, double breakExtensionMm, double breakExtensionSpread,
                    double... interiorShape) {
        this.key = key;
        this.materialName = materialName;
        this.pretensionNewton = pretensionNewton;
        this.breakForceNewton = breakForceNewton;
        this.breakForceSpread = breakForceSpread;
        this.breakExtensionMm = breakExtensionMm;
        this.breakExtensionSpread = breakExtensionSpread;

        this.shape = new double[interiorShape.length + 2];
        System.arraycopy(interiorShape, 0, shape, 1, interiorShape.length);
        shape[shape.length - 1] = 1.0;
    }

    String key() {
        return key;
    }

    double pretensionNewton() {
        return pretensionNewton;
    }

    double breakForceNewton() {
        return breakForceNewton;
    }

    double breakForceSpread() {
        return breakForceSpread;
    }

    double breakExtensionMm() {
        return breakExtensionMm;
    }

    double breakExtensionSpread() {
        return breakExtensionSpread;
    }

    /**
     * Travel from an unloaded specimen to {@code pretension}, at nominal length. No trace shows the
     * specimen below its pretension, so the toe is linear at the curve's first measured slope;
     * release in a cyclic run needs it to reach zero force.
     */
    double toeMm(double pretension) {
        double initialStiffness = shape[1] * (shape.length - 1)
                * (breakForceNewton - pretension) / breakExtensionMm;
        return pretension / initialStiffness;
    }

    /**
     * The normalised curve at {@code x} (extension past pretension over extension at break),
     * linear between knots. Beyond 1 it continues at the last segment's slope, so a specimen drawn
     * stronger than nominal, or one that never breaks, keeps loading.
     */
    double shapeAt(double x) {
        if (x <= 0) {
            return 0;
        }
        int segments = shape.length - 1;
        double scaled = x * segments;
        int i = Math.min((int) scaled, segments - 1);
        return shape[i] + (shape[i + 1] - shape[i]) * (scaled - i);
    }

    /** Matches the profile key, the enum constant or the seeded material name; null when nothing matches. */
    static SpecimenProfile find(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        String wanted = name.trim().toLowerCase(Locale.ROOT);
        for (SpecimenProfile profile : values()) {
            if (profile.key.equals(wanted)
                    || profile.name().toLowerCase(Locale.ROOT).equals(wanted)
                    || (profile.materialName != null && profile.materialName.toLowerCase(Locale.ROOT).equals(wanted))) {
                return profile;
            }
        }
        return null;
    }

    /** {@link #DEFAULT} for a blank name; a name that matches nothing is a configuration error. */
    static SpecimenProfile resolve(String name) {
        if (name == null || name.isBlank()) {
            return DEFAULT;
        }
        SpecimenProfile profile = find(name);
        if (profile == null) {
            throw new IllegalStateException("deck.simulated.profile '" + name + "' matches no specimen profile; known: "
                    + Arrays.stream(values()).map(SpecimenProfile::key).collect(Collectors.joining(", ")));
        }
        return profile;
    }
}
