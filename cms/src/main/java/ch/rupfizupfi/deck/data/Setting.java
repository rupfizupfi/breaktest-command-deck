package ch.rupfizupfi.deck.data;

import java.lang.reflect.Type;

public class Setting<T> {
    public enum Key {
        TESTRUNNER_SUCK("testrunner.suck"),
        TESTRUNNER_SUCK_DURATION("testrunner.suck.duration"),
        FILE_UPLOAD("file.upload.directory"),
        FILE_RESULT_DATA("file.result.data.directory"),
        ;

        private final String key;

        public Type getType() {
            return switch (this) {
                case TESTRUNNER_SUCK -> Boolean.class;
                case TESTRUNNER_SUCK_DURATION -> Integer.class;
                case FILE_UPLOAD, FILE_RESULT_DATA -> String.class;
                default -> throw new IllegalStateException("Unexpected value: " + this);
            };
        }

        Key(String key) {
            this.key = key;
        }

        public String getKey() {
            return key;
        }
    }

    private String key;
    private T value;

    public static <T> Setting<T> create(Key key, T value) {
        return create(key.getKey(), value);
    }

    public static <T> Setting<T> create(String key, T value) {
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