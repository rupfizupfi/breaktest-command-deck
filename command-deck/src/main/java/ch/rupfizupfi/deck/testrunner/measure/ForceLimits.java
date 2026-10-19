package ch.rupfizupfi.deck.testrunner.measure;

/**
 * The limits the measurement loop compares every sample against, newton. Read per sample on the
 * measurement thread; moved by the run loop when a cyclic program adapts its limits through
 * {@code RunControl#adaptLimits}, which validates them first (I13), hence volatile.
 * <p>
 * The ceiling is fixed for the run: {@link Double#POSITIVE_INFINITY} means none, which is the
 * destructive test, whose upper limit already is its shut-off.
 */
public class ForceLimits {
    private volatile double upperLimit;
    private volatile double lowerLimit;
    private final double forceCeiling;

    public ForceLimits(double upperLimit, double lowerLimit, double forceCeiling) {
        this.upperLimit = upperLimit;
        this.lowerLimit = lowerLimit;
        this.forceCeiling = forceCeiling;
    }

    public double getUpperLimit() {
        return upperLimit;
    }

    public double getLowerLimit() {
        return lowerLimit;
    }

    public double getForceCeiling() {
        return forceCeiling;
    }

    /** Both limits; the ceiling stays. */
    public void adapt(double upperLimit, double lowerLimit) {
        this.upperLimit = upperLimit;
        this.lowerLimit = lowerLimit;
    }
}
