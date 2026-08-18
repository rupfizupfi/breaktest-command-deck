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
 * The database is profile-separated but the result <i>files</i> are not: both
 * {@code CSVStoreService} and {@code TestLogger} resolve
 * {@code <resultData>/<testResultId>/…}, and the id comes from whichever database is in play — dev
 * H2 ids are unrelated to production Postgres ids. Without this, a simulated dev run drops a force
 * trace into the same tree real runs use, under an id that means something else there. Since the
 * test outcome is never persisted, that CSV <b>is</b> the durable artefact of a run, so it is
 * exactly the file that could later be mistaken for real material data.
 * <p>
 * Overriding the single accessor both writers share keeps cms untouched: in real mode this bean
 * does not exist and the cms {@code StorageLocationService} is the only candidate.
 */
@Service
@Primary
@ConditionalOnProperty(name = "deck.hardware.mode", havingValue = "simulated")
public class SimulatedStorageLocationService extends StorageLocationService {

    private static final Logger logger = LoggerFactory.getLogger(SimulatedStorageLocationService.class);

    /** Named so it is obvious in a file browser, not just to code. */
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
