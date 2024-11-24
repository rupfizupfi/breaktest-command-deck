package ch.rupfizupfi.deck.filesystem;

import ch.rupfizupfi.deck.data.Setting;
import ch.rupfizupfi.deck.data.SettingRepository;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.nio.file.Paths;

@Service
public class StorageLocationService {
    protected SettingRepository settingRepository;
    public StorageLocationService(SettingRepository settingRepository) {
        this.settingRepository = settingRepository;
    }

    public Path getUploadLocation() {
        String path = settingRepository.getSettingValue(Setting.Key.FILE_UPLOAD);
        return Paths.get(resolvePath(path)).toAbsolutePath().normalize();
    }

    public Path getResultDataLocation() {
        String path = settingRepository.getSettingValue(Setting.Key.FILE_RESULT_DATA);
        return Paths.get(resolvePath(path)).toAbsolutePath().normalize();
    }

    protected String resolvePath(String path) {
        return path.replace("~", System.getProperty("user.home"));
    }
}
