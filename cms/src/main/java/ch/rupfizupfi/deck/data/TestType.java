package ch.rupfizupfi.deck.data;

/**
 * The kind of run a {@link TestParameter} describes. Exactly these three exist (owner decision D1,
 * OQ-64 step 3): every one of them has a runner in command-deck, so a parameter of any type the
 * model can hold is a parameter the bench can run.
 * <p>
 * The column keeps the bytes it had while it was a free-form string: {@link TestTypeConverter}
 * stores {@link #wireName()}, not the constant name, so a cms and a deck on either side of this
 * change read each other's rows. The JSON and the generated TypeScript enum use the constant name.
 */
public enum TestType {
    DESTRUCTIVE("destructive"),
    CYCLIC("cyclic"),
    TIME_CYCLIC("timeCyclic");

    private final String wireName;

    TestType(String wireName) {
        this.wireName = wireName;
    }

    /** The stored column value, also the text of labels, run logs and the results export. */
    public String wireName() {
        return wireName;
    }

    /**
     * @throws IllegalArgumentException naming the value, when it is none of the three
     */
    public static TestType fromWireName(String wireName) {
        for (TestType type : values()) {
            if (type.wireName.equals(wireName)) {
                return type;
            }
        }
        throw new IllegalArgumentException("unknown test parameter type: " + wireName);
    }
}
