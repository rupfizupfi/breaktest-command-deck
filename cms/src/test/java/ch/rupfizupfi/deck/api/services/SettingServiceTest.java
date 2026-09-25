package ch.rupfizupfi.deck.api.services;

import ch.rupfizupfi.deck.data.Setting;
import ch.rupfizupfi.deck.data.SettingRepository;
import com.vaadin.hilla.crud.filter.OrFilter;
import com.vaadin.hilla.crud.filter.PropertyStringFilter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** The grid's filter, sort and page reach the file-backed store, and a failed settings write reaches the browser as an error rather than as a successful save. */
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
}
