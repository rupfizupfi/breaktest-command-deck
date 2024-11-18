package ch.rupfizupfi.deck.data;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Logger;

@Service
public class SettingRepository {
    private static final Logger log = Logger.getLogger(SettingRepository.class.getName());
    private static final String SETTINGS_FILE = "settings.json";

    private final List<Setting<?>> defaultSettings = List.of(Setting.create(Setting.Key.TESTRUNNER_SUCK, true), Setting.create(Setting.Key.TESTRUNNER_SUCK_DURATION, 10));
    private final ObjectMapper objectMapper;
    private List<Setting<?>> settingsCache;

    @Autowired
    public SettingRepository(ObjectMapper objectMapper) throws IOException {
        this.objectMapper = objectMapper;
        initializeSettingsFile();
        this.settingsCache = new CopyOnWriteArrayList<>();
    }

    private void initializeSettingsFile() throws IOException {
        Path settingsFilePath = getSettingFilePath();
        if (Files.notExists(settingsFilePath)) {
            Files.createFile(settingsFilePath);
            objectMapper.writeValue(settingsFilePath.toFile(), defaultSettings);
        }
    }

    private String getEnvironment() {
        return System.getProperty("env", "prod");
    }

    private Path getSettingFilePath() {
        String baseDir = getEnvironment().equals("dev") ? System.getProperty("user.dir") : Paths.get(System.getProperty("user.home"), "breaktester").toString();
        return Paths.get(baseDir, SETTINGS_FILE);
    }

    private List<Setting<?>> getSettings(boolean forceReload) {
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


    public <T> T getSettingValue(Setting.Key key) {
        return (T) getSetting(key.getKey()).getValue();
    }

    public List<Setting<?>> getAllSettings() {
        return getSettings();
    }

    public <T> Setting<T> getSetting(String key) {
        return (Setting<T>) getSettings().stream().filter(setting -> setting.getKey().equals(key)).findFirst().orElseGet(Setting::new);
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