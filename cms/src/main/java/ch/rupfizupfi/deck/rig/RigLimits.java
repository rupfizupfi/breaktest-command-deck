package ch.rupfizupfi.deck.rig;

import ch.rupfizupfi.deck.data.Setting;
import ch.rupfizupfi.deck.data.SettingRepository;

/**
 * What the bench itself can take: the load cell's rated capacity and the fastest crosshead speed
 * a test parameter may ask for, plus the load a destructive run must have built before its fall
 * below the lower shut-off counts as a break that starts the suction. Admin-editable through the
 * {@code rig.*} {@link Setting.Key}s.
 * <p>
 * A run snapshots this once at start, as it does its recovery gates, so a settings edit mid-run
 * never moves the bounds under a running test. Immutable, and valid by construction.
 *
 * @param ratedCapacityKilonewton      the load cell's rated capacity in kN; every shut-off sits inside it
 * @param maxSpeedMillimetresPerMinute the highest crosshead speed in mm/min a parameter may command
 * @param suctionLoadMarginNewton      newton above the lower shut-off the run's peak force must reach
 *                                     for a LOWER crossing to start the suction: below it no load was
 *                                     built, so the specimen was not clamped or pretensioned
 */
public record RigLimits(double ratedCapacityKilonewton, double maxSpeedMillimetresPerMinute,
                        double suctionLoadMarginNewton) {
    /** The bench's load cell: 150 kN, calibrated in newton (owner-confirmed 2026-10-09). */
    public static final int DEFAULT_RATED_CAPACITY_KILONEWTON = 150;
    /**
     * Not a measured limit: nothing in code or docs states the rig's top speed. 500 mm/min is about
     * 1333 motor rpm through the 0.375 mm/rev gearbox, below a 4-pole motor's 1500 rpm synchronous
     * speed at 50 Hz. Conservative on purpose until the owner confirms the real figure.
     */
    public static final int DEFAULT_MAX_SPEED_MILLIMETRES_PER_MINUTE = 500;
    /**
     * Owner decision 2026-10-13. Far above the simulated bench's 10 N noise, so a single spike
     * cannot pass for built load.
     */
    public static final int DEFAULT_SUCTION_LOAD_MARGIN_NEWTON = 500;

    /** Sanity ceiling for the setting, not a rig property: a 1 MN cell is a typo on this frame. */
    public static final int CAPACITY_UPPER_BOUND_KILONEWTON = 1_000;
    /**
     * Sanity ceiling for the setting: about 2667 motor rpm, inside the CFW11 driver's P0683 bound
     * (just under 4 x the motor's synchronous speed) for any 2- to 8-pole motor at 50 or 60 Hz.
     */
    public static final int SPEED_UPPER_BOUND_MILLIMETRES_PER_MINUTE = 1_000;

    public static final RigLimits DEFAULTS = new RigLimits(DEFAULT_RATED_CAPACITY_KILONEWTON,
            DEFAULT_MAX_SPEED_MILLIMETRES_PER_MINUTE, DEFAULT_SUCTION_LOAD_MARGIN_NEWTON);

    /**
     * @throws IllegalArgumentException for a capacity or speed outside (0, upper bound], a margin
     *                                  outside [0, rated capacity], or any value not finite
     */
    public RigLimits {
        requireRatedCapacity(ratedCapacityKilonewton);
        requireMaxSpeed(maxSpeedMillimetresPerMinute);
        requireSuctionLoadMargin(suctionLoadMarginNewton, ratedCapacityKilonewton);
    }

    /** The given capacity and speed with the default suction load margin. */
    public RigLimits(double ratedCapacityKilonewton, double maxSpeedMillimetresPerMinute) {
        this(ratedCapacityKilonewton, maxSpeedMillimetresPerMinute, DEFAULT_SUCTION_LOAD_MARGIN_NEWTON);
    }

    public double ratedCapacityNewton() {
        return ratedCapacityKilonewton * 1000.0;
    }

    /**
     * The current settings. A key missing from the file resolves to its default in
     * {@link SettingRepository#getSetting}, so only a stored value that is no number, or out of
     * range, fails here, and it fails rather than falling back: a hand-edited limit the bench cannot
     * trust must not quietly become 150 kN.
     *
     * @throws IllegalStateException naming the setting that is unusable
     */
    public static RigLimits read(SettingRepository settings) {
        double capacity = number(settings, Setting.Key.RIG_RATED_CAPACITY);
        double speed = number(settings, Setting.Key.RIG_MAX_SPEED);
        double margin = number(settings, Setting.Key.RIG_SUCTION_LOAD_MARGIN);
        try {
            return new RigLimits(capacity, speed, margin);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("the rig limit settings are unusable: " + e.getMessage(), e);
        }
    }

    private static double number(SettingRepository settings, Setting.Key key) {
        Setting<?> setting = settings.getSetting(key.getKey());
        Object value = setting == null ? null : setting.getValue();
        if (!(value instanceof Number number)) {
            throw new IllegalStateException("setting " + key.getKey() + " must be a number, was " + value);
        }
        return number.doubleValue();
    }

    /** @throws IllegalArgumentException unless finite and within (0, {@value #CAPACITY_UPPER_BOUND_KILONEWTON}] kN */
    public static void requireRatedCapacity(double kilonewton) {
        requireWithin(kilonewton, CAPACITY_UPPER_BOUND_KILONEWTON, "rated capacity", "kN");
    }

    /** @throws IllegalArgumentException unless finite and within (0, {@value #SPEED_UPPER_BOUND_MILLIMETRES_PER_MINUTE}] mm/min */
    public static void requireMaxSpeed(double millimetresPerMinute) {
        requireWithin(millimetresPerMinute, SPEED_UPPER_BOUND_MILLIMETRES_PER_MINUTE, "maximum crosshead speed", "mm/min");
    }

    /**
     * @throws IllegalArgumentException unless finite and within [0, the rated capacity in newton]:
     *                                  zero is allowed (any load above the lower shut-off counts), a
     *                                  margin the cell cannot measure would make every break a skip
     */
    public static void requireSuctionLoadMargin(double newton, double ratedCapacityKilonewton) {
        double bound = ratedCapacityKilonewton * 1000.0;
        if (!Double.isFinite(newton) || newton < 0 || newton > bound) {
            throw new IllegalArgumentException("suction load margin must be within [0, " + bound
                    + "] N (the rated capacity), was " + newton);
        }
    }

    private static void requireWithin(double value, int upperBound, String name, String unit) {
        if (!Double.isFinite(value) || value <= 0 || value > upperBound) {
            throw new IllegalArgumentException(name + " must be within (0, " + upperBound + "] " + unit
                    + ", was " + value);
        }
    }
}
