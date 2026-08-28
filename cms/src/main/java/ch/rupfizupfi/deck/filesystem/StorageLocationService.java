package ch.rupfizupfi.deck.filesystem;

import ch.rupfizupfi.deck.data.Setting;
import ch.rupfizupfi.deck.data.SettingRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.nio.file.Paths;

@Service
public class StorageLocationService {
    protected SettingRepository settingRepository;

    /**
     * What {@code ~} in a stored path resolves to. Defaults to {@code user.home}, which is what the
     * settings themselves assume and what works unchanged on a bench machine.
     * <p>
     * It has to be overridable because the containers create {@code appuser} with
     * {@code --home /nonexistent --no-create-home}, so {@code user.home} resolves there and
     * {@code ~/breaktester} never reaches the bind mount — the deck would write run artefacts into
     * a directory that does not exist.
     */
    private final String storageRoot;

    public StorageLocationService(SettingRepository settingRepository,
                                  @Value("${deck.storage.root:#{systemProperties['user.home']}}")
                                  String storageRoot) {
        this.settingRepository = settingRepository;
        this.storageRoot = storageRoot;
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
        return path.replace("~", storageRoot);
    }
}
