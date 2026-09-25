package ch.rupfizupfi.deck.data;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;

/** Every read resolves to a value: the file, then the built-in default, then a valueless setting. */
class SettingRepositoryTest {

    @TempDir
    Path storageRoot;

    private SettingRepository repository;

    @BeforeEach
    void createRepository() {
        repository = new SettingRepository(new JsonMapper());
        // Any profile but dev puts the file under the storage root instead of the working directory.
        ReflectionTestUtils.setField(repository, "activeProfile", "test");
        ReflectionTestUtils.setField(repository, "storageRoot", storageRoot.toString());
    }

    private Path settingsFile() {
        return storageRoot.resolve("breaktester").resolve("settings.json");
    }

    private void writeSettingsFile(String content) throws IOException {
        Files.createDirectories(settingsFile().getParent());
        Files.writeString(settingsFile(), content);
    }

    @Test
    void firstReadCreatesTheFileAndReturnsTheDefaults() {
        assertThat(repository.getAllSettings()).extracting(Setting::getKey).containsExactlyInAnyOrder(
                Setting.Key.TESTRUNNER_SUCK.getKey(),
                Setting.Key.TESTRUNNER_SUCK_DURATION.getKey(),
                Setting.Key.FILE_RESULT_DATA.getKey(),
                Setting.Key.FILE_UPLOAD.getKey());
        assertThat(settingsFile()).exists();
    }

    @Test
    void knownKeyMissingFromTheFileResolvesToItsDefault() throws IOException {
        writeSettingsFile("[{\"key\":\"testrunner.suck\",\"value\":false}]");

        Boolean suck = repository.getSettingValue(Setting.Key.TESTRUNNER_SUCK);
        String uploadDirectory = repository.getSettingValue(Setting.Key.FILE_UPLOAD);

        assertThat(suck).isFalse();
        assertThat(uploadDirectory).isEqualTo("~/breaktester/uploads");
    }

    @Test
    void unknownKeyReturnsAValuelessSettingCarryingThatKey() {
        Setting<?> setting = repository.getSetting("no.such.setting");

        assertThat(setting.getKey()).isEqualTo("no.such.setting");
        assertThat(setting.getValue()).isNull();
        assertThat(setting.getType()).isNull();
    }

    @Test
    void savedValueSurvivesASync() throws IOException {
        repository.saveSetting(Setting.create(Setting.Key.TESTRUNNER_SUCK_DURATION, 42));
        repository.syncAndGetSettings();

        assertThat(repository.getSetting(Setting.Key.TESTRUNNER_SUCK_DURATION.getKey()).getValue()).isEqualTo(42);
    }

    @Test
    void unparseableFileFallsBackToTheDefaults() throws IOException {
        writeSettingsFile("not json");

        assertThatNoException().isThrownBy(() -> repository.getAllSettings());
        assertThat(repository.getAllSettings().stream().map(setting -> (Object) setting.getValue()).toList())
                .contains(true, 10, "~/breaktester", "~/breaktester/uploads");
    }
}
