package ch.rupfizupfi.deck.api.services;

import ch.rupfizupfi.deck.data.Setting;
import ch.rupfizupfi.deck.data.SettingRepository;
import com.vaadin.hilla.crud.filter.OrFilter;
import com.vaadin.hilla.exception.EndpointException;
import com.vaadin.hilla.crud.filter.PropertyStringFilter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The grid's filter, sort and page reach the file-backed store, and a failed settings write reaches the browser as an error rather than as a successful save.
 * A save names a known key with a value of its type, and a directory setting stays out of the application's own directories.
 */
class SettingServiceTest {

    private static final String SUCK = Setting.Key.TESTRUNNER_SUCK.getKey();
    private static final String SUCK_DURATION = Setting.Key.TESTRUNNER_SUCK_DURATION.getKey();
    private static final String RESULT_DATA = Setting.Key.FILE_RESULT_DATA.getKey();
    private static final String UPLOAD = Setting.Key.FILE_UPLOAD.getKey();

    private SettingRepository repository;
    private SettingService service;

    @BeforeEach
    void createService() {
        repository = mock(SettingRepository.class);
        service = new SettingService(repository);
    }

    private void seedSettings() {
        when(repository.getAllSettings()).thenReturn(List.<Setting<?>>of(
                Setting.create(Setting.Key.TESTRUNNER_SUCK, true),
                Setting.create(Setting.Key.TESTRUNNER_SUCK_DURATION, 10),
                Setting.create(Setting.Key.FILE_RESULT_DATA, "~/breaktester"),
                Setting.create(Setting.Key.FILE_UPLOAD, "~/breaktester/uploads")
        ));
    }

    private static PropertyStringFilter filter(String propertyId, PropertyStringFilter.Matcher matcher, String value) {
        var filter = new PropertyStringFilter();
        filter.setPropertyId(propertyId);
        filter.setMatcher(matcher);
        filter.setFilterValue(value);
        return filter;
    }

    private static List<String> keysOf(List<Setting<?>> settings) {
        return settings.stream().map(Setting::getKey).toList();
    }

    @Test
    void listWithoutFilterOrPagingReturnsEveryStoredSetting() {
        seedSettings();

        assertThat(keysOf(service.list(Pageable.unpaged(), null)))
                .containsExactly(SUCK, SUCK_DURATION, RESULT_DATA, UPLOAD);
    }

    @Test
    void listAppliesAContainsFilterOnTheKeyCaseInsensitively() {
        seedSettings();

        var result = service.list(Pageable.unpaged(), filter("key", PropertyStringFilter.Matcher.CONTAINS, "Suck"));

        assertThat(keysOf(result)).containsExactly(SUCK, SUCK_DURATION);
    }

    @Test
    void listAppliesAnEqualsFilterOnTheValue() {
        seedSettings();

        var result = service.list(Pageable.unpaged(), filter("value", PropertyStringFilter.Matcher.EQUALS, "10"));

        assertThat(keysOf(result)).containsExactly(SUCK_DURATION);
    }

    @Test
    void listUnionsTheChildrenOfAnOrFilter() {
        seedSettings();

        var result = service.list(Pageable.unpaged(), new OrFilter(
                filter("key", PropertyStringFilter.Matcher.EQUALS, SUCK),
                filter("key", PropertyStringFilter.Matcher.EQUALS, UPLOAD)));

        assertThat(keysOf(result)).containsExactly(SUCK, UPLOAD);
    }

    @Test
    void listMatchesNothingForAnUnknownProperty() {
        seedSettings();

        var result = service.list(Pageable.unpaged(), filter("owner", PropertyStringFilter.Matcher.CONTAINS, "suck"));

        assertThat(result).isEmpty();
    }

    @Test
    void listReturnsTheRequestedPage() {
        seedSettings();

        assertThat(keysOf(service.list(PageRequest.of(0, 2), null))).containsExactly(SUCK, SUCK_DURATION);
        assertThat(keysOf(service.list(PageRequest.of(1, 2), null))).containsExactly(RESULT_DATA, UPLOAD);
    }

    @Test
    void listSortsByKeyDescending() {
        seedSettings();

        var result = service.list(PageRequest.of(0, 10, Sort.by(Sort.Direction.DESC, "key")), null);

        assertThat(keysOf(result)).containsExactly(SUCK_DURATION, SUCK, UPLOAD, RESULT_DATA);
    }

    @Test
    void saveRethrowsTheWriteFailureNamingTheKey() throws IOException {
        Setting<String> setting = Setting.create(Setting.Key.FILE_UPLOAD, "~/breaktester/uploads");
        doThrow(new IOException("read-only filesystem")).when(repository).saveSetting(setting);

        assertThatExceptionOfType(UncheckedIOException.class)
                .isThrownBy(() -> service.save(setting))
                .withMessageContaining(Setting.Key.FILE_UPLOAD.getKey());
    }

    @Test
    void deleteRethrowsTheWriteFailureNamingTheKey() throws IOException {
        String key = Setting.Key.TESTRUNNER_SUCK.getKey();
        doThrow(new IOException("read-only filesystem")).when(repository).deleteSetting(key);

        assertThatExceptionOfType(UncheckedIOException.class)
                .isThrownBy(() -> service.delete(key))
                .withMessageContaining(key);
    }

    @Test
    void saveReturnsTheStoredSetting() {
        Setting<Integer> setting = Setting.create(Setting.Key.TESTRUNNER_SUCK_DURATION, 20);

        assertThat(service.save(setting)).isSameAs(setting);
    }

    @Test
    void anUnknownKeyIsRefusedBeforeTheStoreIsTouched() throws IOException {
        Setting<String> setting = Setting.create("spring.datasource.url", "jdbc:h2:mem:x");

        assertThatExceptionOfType(EndpointException.class).isThrownBy(() -> service.save(setting));
        verify(repository, never()).saveSetting(any());
    }

    @Test
    void aValueOfTheWrongTypeIsRefused() throws IOException {
        Setting<String> setting = Setting.create(Setting.Key.TESTRUNNER_SUCK_DURATION.getKey(), "ten");

        assertThatExceptionOfType(EndpointException.class).isThrownBy(() -> service.save(setting));
        verify(repository, never()).saveSetting(any());
    }

    @Test
    void aRelativeDirectoryIsRefused() throws IOException {
        Setting<String> setting = Setting.create(Setting.Key.FILE_UPLOAD, "uploads");

        assertThatExceptionOfType(EndpointException.class).isThrownBy(() -> service.save(setting));
        verify(repository, never()).saveSetting(any());
    }

    @Test
    void aDirectoryWithParentSegmentsIsRefused(@TempDir Path elsewhere) throws IOException {
        Setting<String> setting = Setting.create(Setting.Key.FILE_UPLOAD, elsewhere + "/../uploads");

        assertThatExceptionOfType(EndpointException.class).isThrownBy(() -> service.save(setting));
        verify(repository, never()).saveSetting(any());
    }

    @Test
    void aDirectoryInsideTheWorkingDirectoryIsRefused() throws IOException {
        String insideApp = Paths.get(System.getProperty("user.dir"), "drivers").toAbsolutePath().toString();
        Setting<String> setting = Setting.create(Setting.Key.FILE_RESULT_DATA, insideApp);

        assertThatExceptionOfType(EndpointException.class).isThrownBy(() -> service.save(setting));
        verify(repository, never()).saveSetting(any());
    }

    @Test
    void aDirectoryInsideTheLoaderPathIsRefused(@TempDir Path drivers) throws IOException {
        String previous = System.getProperty("loader.path");
        System.setProperty("loader.path", drivers.toString());
        try {
            Setting<String> setting = Setting.create(Setting.Key.FILE_UPLOAD, drivers.resolve("uploads").toString());

            assertThatExceptionOfType(EndpointException.class).isThrownBy(() -> service.save(setting));
            verify(repository, never()).saveSetting(any());
        } finally {
            if (previous == null) {
                System.clearProperty("loader.path");
            } else {
                System.setProperty("loader.path", previous);
            }
        }
    }

    @Test
    void anAbsoluteDirectoryOutsideTheApplicationIsSaved(@TempDir Path elsewhere) throws IOException {
        Setting<String> setting = Setting.create(Setting.Key.FILE_UPLOAD, elsewhere.resolve("uploads").toString());

        assertThat(service.save(setting)).isSameAs(setting);
        verify(repository).saveSetting(setting);
    }

    @Test
    void rigLimitsOutOfRangeAreRefusedNamingTheKey() throws IOException {
        for (Setting<Integer> setting : List.of(
                Setting.create(Setting.Key.RIG_RATED_CAPACITY, 0),
                Setting.create(Setting.Key.RIG_RATED_CAPACITY, 1_001),
                Setting.create(Setting.Key.RIG_MAX_SPEED, -5),
                Setting.create(Setting.Key.RIG_MAX_SPEED, 1_001))) {
            assertThatExceptionOfType(EndpointException.class)
                    .isThrownBy(() -> service.save(setting))
                    .withMessageContaining(setting.getKey());
        }
        verify(repository, never()).saveSetting(any());
    }

    @Test
    void aRigLimitThatIsNoIntegerIsRefused() throws IOException {
        Setting<Double> setting = Setting.create(Setting.Key.RIG_RATED_CAPACITY, 150.5);

        assertThatExceptionOfType(EndpointException.class).isThrownBy(() -> service.save(setting));
        verify(repository, never()).saveSetting(any());
    }

    @Test
    void aSuctionLoadMarginFromZeroUpToTheStoredCapacityIsSaved() throws IOException {
        storedSetting(Setting.Key.RIG_RATED_CAPACITY, 10);
        Setting<Integer> zero = Setting.create(Setting.Key.RIG_SUCTION_LOAD_MARGIN, 0);
        Setting<Integer> atCapacity = Setting.create(Setting.Key.RIG_SUCTION_LOAD_MARGIN, 10_000);

        assertThat(service.save(zero)).isSameAs(zero);
        assertThat(service.save(atCapacity)).isSameAs(atCapacity);
        verify(repository).saveSetting(zero);
        verify(repository).saveSetting(atCapacity);
    }

    @Test
    void aSuctionLoadMarginNegativeOrAboveTheStoredCapacityIsRefusedNamingTheKey() throws IOException {
        storedSetting(Setting.Key.RIG_RATED_CAPACITY, 10);
        for (Setting<Integer> setting : List.of(
                Setting.create(Setting.Key.RIG_SUCTION_LOAD_MARGIN, -1),
                Setting.create(Setting.Key.RIG_SUCTION_LOAD_MARGIN, 10_001))) {
            assertThatExceptionOfType(EndpointException.class)
                    .isThrownBy(() -> service.save(setting))
                    .withMessageContaining(setting.getKey())
                    .withMessageContaining("suction load margin");
        }
        verify(repository, never()).saveSetting(any());
    }

    @Test
    void aSuctionLoadMarginWithNoUsableStoredCapacityIsHeldToTheCapacitySanityCeiling() throws IOException {
        storedSetting(Setting.Key.RIG_RATED_CAPACITY, "lots");
        Setting<Integer> margin = Setting.create(Setting.Key.RIG_SUCTION_LOAD_MARGIN, 500);
        Setting<Integer> beyond = Setting.create(Setting.Key.RIG_SUCTION_LOAD_MARGIN, 1_000_001);

        assertThat(service.save(margin)).isSameAs(margin);
        assertThatExceptionOfType(EndpointException.class).isThrownBy(() -> service.save(beyond));
        verify(repository, never()).saveSetting(beyond);
    }

    @Test
    void aCapacityBelowTheStoredSuctionLoadMarginIsRefused() throws IOException {
        storedSetting(Setting.Key.RIG_SUCTION_LOAD_MARGIN, 20_000);
        Setting<Integer> capacity = Setting.create(Setting.Key.RIG_RATED_CAPACITY, 10);

        assertThatExceptionOfType(EndpointException.class)
                .isThrownBy(() -> service.save(capacity))
                .withMessageContaining(capacity.getKey())
                .withMessageContaining("suction load margin");
        verify(repository, never()).saveSetting(any());
    }

    private void storedSetting(Setting.Key key, Object value) {
        doReturn(Setting.create(key, value)).when(repository).getSetting(key.getKey());
    }

    @Test
    void rigLimitsInRangeAreSaved() throws IOException {
        Setting<Integer> capacity = Setting.create(Setting.Key.RIG_RATED_CAPACITY, 150);
        Setting<Integer> speed = Setting.create(Setting.Key.RIG_MAX_SPEED, 500);

        assertThat(service.save(capacity)).isSameAs(capacity);
        assertThat(service.save(speed)).isSameAs(speed);
        verify(repository).saveSetting(capacity);
        verify(repository).saveSetting(speed);
    }
}
