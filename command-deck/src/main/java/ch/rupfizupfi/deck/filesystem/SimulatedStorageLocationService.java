package ch.rupfizupfi.deck.filesystem;

import ch.rupfizupfi.deck.data.SettingRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

import java.nio.file.Path;

/**
 * Diverts simulated runs into their own result tree.
 * <p>
 * The database is profile-separated but the result files are not: {@code CSVStoreService} and
 * {@code TestLogger} both resolve {@code <resultData>/<testResultId>/…}, and dev H2 ids are
 * unrelated to production ids, so a simulated force trace could otherwise be mistaken for real
 * material data.
 * <p>
 * In real mode this bean does not exist and the cms {@code StorageLocationService} is used.
 */
@Service
@Primary
@ConditionalOnProperty(name = "deck.hardware.mode", havingValue = "simulated")
public class SimulatedStorageLocationService extends StorageLocationService {

    private static final Logger logger = LoggerFactory.getLogger(SimulatedStorageLocationService.class);

    public static final String SIMULATED_SUBDIRECTORY = "simulated";

    public SimulatedStorageLocationService(SettingRepository settingRepository) {
        super(settingRepository);
        logger.warn("SIMULATED HARDWARE: run artefacts are written under .../{}/ and are NOT"
                + " measurements of a real specimen", SIMULATED_SUBDIRECTORY);
    }

    @Override
    public Path getResultDataLocation() {
        return super.getResultDataLocation().resolve(SIMULATED_SUBDIRECTORY);
    }
}
