package ch.rupfizupfi.deck.data;

import jakarta.annotation.Nullable;

import java.lang.reflect.Type;

public class Setting<T> {
    public enum Key {
        TESTRUNNER_SUCK("testrunner.suck"),
        TESTRUNNER_SUCK_DURATION("testrunner.suck.duration"),
        FILE_UPLOAD("file.upload.directory"),
        FILE_RESULT_DATA("file.result.data.directory"),
        /** The load cell's rated capacity in kN; bounds every shut-off threshold. See {@code RigLimits}. */
        RIG_RATED_CAPACITY("rig.loadcell.capacity.kn"),
        /** The fastest crosshead speed in mm/min a test parameter may command. See {@code RigLimits}. */
        RIG_MAX_SPEED("rig.crosshead.speed.max.mm-per-min"),
        /**
         * Newton above the lower shut-off a destructive run's peak must reach before its LOWER crossing
         * counts as a break that starts the suction. See {@code RigLimits}.
         */
        RIG_SUCTION_LOAD_MARGIN("rig.suction.load-margin.n"),
        ;

        private final String key;

        public Type getType() {
            return switch (this) {
                case TESTRUNNER_SUCK -> Boolean.class;
                case TESTRUNNER_SUCK_DURATION, RIG_RATED_CAPACITY, RIG_MAX_SPEED, RIG_SUCTION_LOAD_MARGIN -> Integer.class;
                case FILE_UPLOAD, FILE_RESULT_DATA -> String.class;
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

    public String getKey() {
        return key;
    }

    public void setKey(String key) {
        this.key = key;
    }

    public T getValue() {
        return value;
    }

    /** The value's runtime class name, driving the frontend's field renderer; null for a key with no value. */
    public @Nullable String getType() {
        return value == null ? null : value.getClass().getName();
    }

    public void setValue(T value) {
        this.value = value;
    }
}