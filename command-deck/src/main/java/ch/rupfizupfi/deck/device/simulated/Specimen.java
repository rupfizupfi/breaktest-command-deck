package ch.rupfizupfi.deck.device.simulated;

import java.util.random.RandomGenerator;

/**
 * One mounted specimen: a {@link SpecimenProfile} with its own break force and length drawn from
 * the profile's batch scatter, so consecutive simulated runs differ the way consecutive real ones do.
 * <p>
 * Position is crosshead travel from an unloaded specimen: zero force at 0, pretension at
 * {@link #mountPositionMm()}, where a fresh specimen starts. The pretension is the operator's, so
 * it may differ from the profile's median (see {@code SimulatedSpecimenSelector}); the curve then
 * runs from it to the same break force.
 */
final class Specimen {

    /** Draws beyond this many standard deviations are clamped; the batches never showed one. */
    private static final double MAX_SIGMA = 2.0;

    /** A draw never shortens the specimen below this fraction of nominal length. */
    private static final double MIN_LENGTH_SCALE = 0.3;

    private final SpecimenProfile profile;
    private final double pretensionNewton;
    private final double breakForceNewton;
    private final double lengthScale;

    private Specimen(SpecimenProfile profile, double pretensionNewton, double breakForceNewton, double lengthScale) {
        this.profile = profile;
        this.pretensionNewton = pretensionNewton;
        this.breakForceNewton = breakForceNewton;
        this.lengthScale = lengthScale;
    }

    /** The profile's medians exactly. */
    static Specimen nominal(SpecimenProfile profile) {
        return nominal(profile, profile.pretensionNewton());
    }

    static Specimen nominal(SpecimenProfile profile, double pretensionNewton) {
        return new Specimen(profile, pretensionNewton, profile.breakForceNewton(), 1.0);
    }

    static Specimen draw(SpecimenProfile profile, double pretensionNewton, RandomGenerator random) {
        double breakForce = profile.breakForceNewton() * (1 + profile.breakForceSpread() * gaussian(random));
        double lengthScale = Math.max(MIN_LENGTH_SCALE, 1 + profile.breakExtensionSpread() * gaussian(random));
        return new Specimen(profile, pretensionNewton, breakForce, lengthScale);
    }

    private static double gaussian(RandomGenerator random) {
        return Math.clamp(random.nextGaussian(), -MAX_SIGMA, MAX_SIGMA);
    }

    SpecimenProfile profile() {
        return profile;
    }

    double pretensionNewton() {
        return pretensionNewton;
    }

    double breakForceNewton() {
        return breakForceNewton;
    }

    double mountPositionMm() {
        return profile.toeMm(pretensionNewton) * lengthScale;
    }

    /** Force on the intact specimen; the caller decides whether it has already broken. */
    double forceAt(double positionMm) {
        if (positionMm <= 0) {
            return 0;
        }

        double toe = mountPositionMm();
        double pretension = pretensionNewton;
        if (positionMm < toe) {
            return pretension * positionMm / toe;
        }

        double x = (positionMm - toe) / (profile.breakExtensionMm() * lengthScale);
        return pretension + (profile.breakForceNewton() - pretension) * profile.shapeAt(x);
    }
}
