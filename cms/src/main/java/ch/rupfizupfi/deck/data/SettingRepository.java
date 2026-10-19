package ch.rupfizupfi.deck.data;

import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import ch.rupfizupfi.deck.rig.RigLimits;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

@Service
public class SettingRepository {
    private static final Logger log = LoggerFactory.getLogger(SettingRepository.class);
    private static final String SETTINGS_FILE = "settings.json";

    @Value("${spring.profiles.active:dev}")
    private String activeProfile;

    private final List<Setting<?>> defaultSettings = List.of(
            Setting.create(Setting.Key.TESTRUNNER_SUCK, true),
            Setting.create(Setting.Key.TESTRUNNER_SUCK_DURATION, 10),
            Setting.create(Setting.Key.FILE_RESULT_DATA, "~/breaktester"),
            Setting.create(Setting.Key.FILE_UPLOAD, "~/breaktester/uploads"),
            Setting.create(Setting.Key.RIG_RATED_CAPACITY, RigLimits.DEFAULT_RATED_CAPACITY_KILONEWTON),
            Setting.create(Setting.Key.RIG_MAX_SPEED, RigLimits.DEFAULT_MAX_SPEED_MILLIMETRES_PER_MINUTE),
            Setting.create(Setting.Key.RIG_SUCTION_LOAD_MARGIN, RigLimits.DEFAULT_SUCTION_LOAD_MARGIN_NEWTON)
    );

    private final ObjectMapper objectMapper;
    private volatile List<Setting<?>> settingsCache;
    private volatile boolean initialized = false;

    @Autowired
    public SettingRepository(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.settingsCache = new CopyOnWriteArrayList<>();
    }

    protected void init() {
        if (!initialized) {
            log.info("init settings: {}", getSettingFilePath());
            try {
                initializeSettingsFile();
            } catch (IOException | JacksonException e) {
                // A bench must still run a test with an unwritable settings file.
                log.error("Cannot create settings file {}, using built-in defaults", getSettingFilePath(), e);
            }
            initialized = true;
        }
    }

    private void initializeSettingsFile() throws IOException {
        Path settingsFilePath = getSettingFilePath();
        if (Files.notExists(settingsFilePath)) {
            // ~/breaktester may not exist yet on a fresh machine.
            Files.createDirectories(settingsFilePath.getParent());
            Files.createFile(settingsFilePath);
            objectMapper.writeValue(settingsFilePath.toFile(), defaultSettings);
        }
    }

    private String getEnvironment() {
        return activeProfile.toLowerCase(Locale.ROOT);
    }

    private Path getSettingFilePath() {
        String baseDir = getEnvironment().equals("dev") ? Paths.get(System.getProperty("user.dir")).toString() : Paths.get(System.getProperty("user.home"), "breaktester").toString();
        return Paths.get(baseDir, SETTINGS_FILE);
    }

    private List<Setting<?>> getSettings(boolean forceReload) {
        init();
        if (forceReload || settingsCache.isEmpty()) {
            settingsCache = new CopyOnWriteArrayList<>(loadSettingsFromJson());
        }
        return settingsCache;
    }

    public List<Setting<?>> syncAndGetSettings() {
        return getSettings(true);
    }

    @SuppressWarnings("unchecked")
    public <T> T getSettingValue(Setting.Key key) {
        return (T) getSetting(key.getKey()).getValue();
    }

    public List<Setting<?>> getAllSettings() {
        return getSettings(false);
    }

    /** The stored setting, else the built-in default for the key, else a valueless {@link Setting} carrying the key. */
    public Setting<?> getSetting(String key) {
        return findByKey(getSettings(false), key)
                .or(() -> findByKey(defaultSettings, key))
                .orElseGet(() -> Setting.create(key, null));
    }

    private static Optional<Setting<?>> findByKey(List<Setting<?>> settings, String key) {
        return settings.stream().filter(setting -> key.equals(setting.getKey())).findFirst();
    }

    public <T> void saveSetting(Setting<T> setting) throws IOException {
        List<Setting<?>> settings = getSettings(true);
        settings.removeIf(s -> s.getKey().equals(setting.getKey()));
        settings.add(setting);
        saveSettingsToJson(settings);
    }

    public void deleteSetting(String key) throws IOException {
        List<Setting<?>> settings = getSettings(true);
        settings.removeIf(s -> s.getKey().equals(key));
        saveSettingsToJson(settings);
    }

    private void saveSettingsToJson(List<Setting<?>> settings) throws IOException {
        objectMapper.writeValue(getSettingFilePath().toFile(), settings.stream().map(setting -> {
            var settingItem = new SettingItem<>();
            settingItem.key = setting.getKey();
            settingItem.value = setting.getValue();
            return settingItem;
        }).toList());
    }

    private List<Setting<?>> loadSettingsFromJson() {
        var file = getSettingFilePath().toFile();
        if (!file.exists()) {
            return defaultSettings;
        }

        try {
            return withMissingDefaults(objectMapper.readValue(file, new TypeReference<List<Setting<?>>>() {
            }));
        } catch (JacksonException e) {
            // Jackson 3 read failures are unchecked JacksonException.
            log.error("Cannot read settings file {}, using built-in defaults", file, e);
            return defaultSettings;
        }
    }

    /**
     * Appends the default of every key the file lacks, so a key added after the file was first
     * written still shows up in the settings view, where an admin can edit it. Written back on the
     * next save.
     */
    private List<Setting<?>> withMissingDefaults(List<Setting<?>> stored) {
        List<Setting<?>> merged = new ArrayList<>(stored);
        for (Setting<?> fallback : defaultSettings) {
            if (findByKey(stored, fallback.getKey()).isEmpty()) {
                merged.add(Setting.create(fallback.getKey(), fallback.getValue()));
            }
        }
        return merged;
    }

    private static class SettingItem<T> {
        public String key;
        public T value;
    }
}
