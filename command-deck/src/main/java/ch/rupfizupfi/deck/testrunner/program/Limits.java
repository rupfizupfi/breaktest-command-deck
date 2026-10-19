package ch.rupfizupfi.deck.testrunner.program;

/**
 * A run's force limits and ceiling, in newton (I13).
 * <p>
 * The two limits are where the measurement loop reports a crossing: the destructive test's
 * shut-off thresholds, the cyclic types' turn forces. The ceiling bounds a cyclic run whose limits
 * move every cycle and whose reversal may never come; {@link Double#POSITIVE_INFINITY} means none,
 * which is the destructive test, whose upper limit already is its shut-off.
 * <p>
 * A non-finite limit disables the shut-off (every comparison against NaN is false), and the cyclic
 * types derive later limits from it, so it is refused here, on every construction - an adaptation
 * builds a new value through the same check.
 */
public record Limits(double upper, double lower, double ceiling) {

    public Limits {
        requireFinite(upper, "upper");
        requireFinite(lower, "lower");
        if (ceiling != Double.POSITIVE_INFINITY) {
            requireFinite(ceiling, "ceiling");
        }
    }

    /** Two limits and no ceiling beyond them. */
    public static Limits withoutCeiling(double upper, double lower) {
        return new Limits(upper, lower, Double.POSITIVE_INFINITY);
    }

    /** Two limits and a finite ceiling; an infinite one is refused rather than read as none. */
    public static Limits withCeiling(double upper, double lower, double ceiling) {
        return new Limits(upper, lower, requireFinite(ceiling, "ceiling"));
    }

    /** The same ceiling, new limits. */
    public Limits adapted(double newUpper, double newLower) {
        return new Limits(newUpper, newLower, ceiling);
    }

    /** The larger magnitude of the two limits; the recovery gate's drift is a fraction of it. */
    public double envelope() {
        return Math.max(Math.abs(upper), Math.abs(lower));
    }

    private static double requireFinite(double value, String which) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException("refusing a non-finite " + which + " force limit: " + value);
        }
        return value;
    }
}
