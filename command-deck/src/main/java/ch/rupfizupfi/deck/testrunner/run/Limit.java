package ch.rupfizupfi.deck.testrunner.run;

/** Which of the run's two force limits a crossing went past. */
public enum Limit {
    UPPER,
    LOWER;

    /**
     * The reversal this crossing calls for. Also the identity the repeat filter shares with a timed
     * turn: an upper crossing and a timed release are the same edge.
     */
    public Direction turn() {
        return this == UPPER ? Direction.RELEASE : Direction.PULL;
    }
}
