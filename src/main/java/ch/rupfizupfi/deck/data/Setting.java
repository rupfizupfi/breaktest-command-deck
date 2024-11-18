package ch.rupfizupfi.deck.data;

public class Setting<T> {
    public enum Key {
        TESTRUNNER_SUCK("testrunner.suck"),
        TESTRUNNER_SUCK_DURATION("testrunner.suck.duration");

        private final String key;

        Key(String key) {
            this.key = key;
        }

        public String getKey() {
            return key;
        }
    }

    private String key;
    private T value;

    public static <T> Setting<T> create (Key key, T value) {
        return create(key.getKey(), value);
    }

    public static <T> Setting<T> create (String key, T value) {
        var setting = new Setting<T>();
        setting.setKey(key);
        setting.setValue(value);
        return setting;
    }

    // Getters and setters
    public String getKey() {
        return key;
    }

    public void setKey(String key) {
        this.key = key;
    }

    public T getValue() {
        return value;
    }

    public String getType() {
        return value.getClass().getName();
    }

    public void setValue(T value) {
        this.value = value;
    }
}