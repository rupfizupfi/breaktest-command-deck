package ch.rupfizupfi.deck.data;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Logger;

@Service
public class SettingRepository {
    private static final Logger log = Logger.getLogger(SettingRepository.class.getName());
    private static final String SETTINGS_FILE = "settings.json";

    @Value("${spring.profiles.active:dev}")
    private String activeProfile;

    private final List<Setting<?>> defaultSettings = List.of(
            Setting.create(Setting.Key.TESTRUNNER_SUCK, true),
            Setting.create(Setting.Key.TESTRUNNER_SUCK_DURATION, 10),
            Setting.create(Setting.Key.FILE_RESULT_DATA, "~/breaktester"),
            Setting.create(Setting.Key.FILE_UPLOAD, "~/breaktester/uploads")
    );

    private final ObjectMapper objectMapper;
    private List<Setting<?>> settingsCache;
    private boolean initialized = false;

    @Autowired
    public SettingRepository(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.settingsCache = new CopyOnWriteArrayList<>();
    }

    protected void init() {
        if (!initialized) {
            try {
                initializeSettingsFile();
            } catch (IOException e) {
                log.throwing(SettingRepository.class.getName(), "init", e);
            }
            initialized = true;
        }
    }

    private void initializeSettingsFile() throws IOException {
        Path settingsFilePath = getSettingFilePath();
        if (Files.notExists(settingsFilePath)) {
            Files.createFile(settingsFilePath);
            objectMapper.writeValue(settingsFilePath.toFile(), defaultSettings);
        }
    }

    private String getEnvironment() {
        return activeProfile.toLowerCase(Locale.ROOT);
    }

    private Path getSettingFilePath() {
        log.info("Environment: " + getEnvironment());
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

    public List<Setting<?>> getSettings() {
        return getSettings(false);
    }

    public List<Setting<?>> syncAndGetSettings() {
        return getSettings(true);
    }

    @SuppressWarnings("unchecked")
    public <T> T getSettingValue(Setting.Key key) {
        return (T) getSetting(key.getKey()).getValue();
    }

    public List<Setting<?>> getAllSettings() {
        return getSettings();
    }

    public <T> Setting<?> getSetting(String key) {
        return getSettings().stream().filter(setting -> setting.getKey().equals(key)).findFirst().orElseGet(Setting::new);
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
            return objectMapper.readValue(file, new TypeReference<List<Setting<?>>>() {
            });
        } catch (IOException e) {
            log.throwing(SettingRepository.class.getName(), "loadSettingsFromJson", e);
            return defaultSettings;
        }
    }

    private static class SettingItem<T> {
        public String key;
        public T value;
    }
}