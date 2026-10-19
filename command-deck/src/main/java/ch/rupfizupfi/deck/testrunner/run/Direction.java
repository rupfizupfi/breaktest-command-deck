package ch.rupfizupfi.deck.testrunner.run;

/**
 * The way the drive moves the crosshead. {@code Drive#setDirection(true)} is {@link #RELEASE}.
 */
public enum Direction {
    /** Unloads the specimen; the reversal an upper-limit crossing calls for. */
    RELEASE,
    /** Loads the specimen; the reversal a lower-limit crossing calls for. */
    PULL;

    /** As {@code Drive#setDirection} takes it. */
    public boolean isRelease() {
        return this == RELEASE;
    }

    public Direction opposite() {
        return this == RELEASE ? PULL : RELEASE;
    }
}
