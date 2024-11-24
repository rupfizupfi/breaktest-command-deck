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
        return Paths.get(settingRepository.getSettingValue(Setting.Key.FILE_UPLOAD)).toAbsolutePath().normalize();
    }

    public Path getResultDataLocation() {
        return Paths.get(settingRepository.getSettingValue(Setting.Key.FILE_RESULT_DATA)).toAbsolutePath().normalize();
    }
}
