package ch.rupfizupfi.deck.api.services;

import ch.rupfizupfi.deck.data.Setting;
import ch.rupfizupfi.deck.data.SettingRepository;
import com.vaadin.hilla.BrowserCallable;
import org.springframework.lang.NonNull;
import org.springframework.lang.Nullable;
import com.vaadin.hilla.crud.CrudService;
import com.vaadin.hilla.crud.filter.AndFilter;
import com.vaadin.hilla.crud.filter.Filter;
import com.vaadin.hilla.crud.filter.OrFilter;
import com.vaadin.hilla.crud.filter.PropertyStringFilter;
import jakarta.annotation.security.PermitAll;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/** Implements {@link CrudService} directly: {@link Setting} is file-backed, not a JPA entity, so {@code CrudRepositoryService} does not apply.
 * The store is a JSON file, so {@link #list} filters, sorts and pages in memory instead of through Hilla's {@code JpaFilterConverter}. */
@BrowserCallable
@PermitAll
public class SettingService implements CrudService<Setting<?>, String> {
    private final SettingRepository repository;

    SettingService(SettingRepository service) {
        this.repository = service;
    }

    public Setting<?> getSetting(String key) {
        return repository.getSetting(key);
    }

    public @NonNull List<Setting<?>> sync() {
        return repository.syncAndGetSettings();
    }

    @Override
    public @Nullable Setting<?> save(Setting<?> value) {
        try {
            repository.saveSetting(value);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot save setting " + value.getKey(), e);
        }
        return value;
    }

    @Override
    public void delete(String s) {
        try {
            repository.deleteSetting(s);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot delete setting " + s, e);
        }
    }

    @Override
    public @NonNull List<Setting<?>> list(Pageable pageable, @Nullable Filter filter) {
        Stream<Setting<?>> settings = repository.getAllSettings().stream()
                .filter(setting -> matches(setting, filter));
        Comparator<Setting<?>> order = comparator(pageable.getSort());
        if (order != null) {
            settings = settings.sorted(order);
        }
        if (pageable.isPaged()) {
            settings = settings.skip(pageable.getOffset()).limit(pageable.getPageSize());
        }
        return settings.toList();
    }

    /** Whether the setting satisfies the grid's filter tree; a null filter accepts everything, an unsupported {@link Filter} accepts nothing. */
    private static boolean matches(Setting<?> setting, @Nullable Filter filter) {
        return switch (filter) {
            case null -> true;
            case AndFilter and -> and.getChildren().stream().allMatch(child -> matches(setting, child));
            case OrFilter or -> or.getChildren().stream().anyMatch(child -> matches(setting, child));
            case PropertyStringFilter property -> matchesProperty(setting, property);
            default -> false;
        };
    }

    private static boolean matchesProperty(Setting<?> setting, PropertyStringFilter filter) {
        String actual = property(setting, filter.getPropertyId());
        if (actual == null) {
            return false;
        }
        String expected = filter.getFilterValue();
        return switch (filter.getMatcher()) {
            case CONTAINS -> actual.toLowerCase(Locale.ROOT).contains(expected.toLowerCase(Locale.ROOT));
            case EQUALS -> actual.equalsIgnoreCase(expected);
            case GREATER_THAN -> compare(actual, expected) > 0;
            case LESS_THAN -> compare(actual, expected) < 0;
        };
    }

    /** The filterable and sortable properties as strings; null for a property the grid cannot resolve on a {@link Setting}. */
    private static @Nullable String property(Setting<?> setting, String propertyId) {
        return switch (propertyId) {
            case "key" -> setting.getKey();
            case "value" -> setting.getValue() == null ? null : String.valueOf(setting.getValue());
            case "type" -> setting.getType();
            default -> null;
        };
    }

    /** Numeric where both sides are numbers, lexicographic otherwise. */
    private static int compare(String actual, String expected) {
        try {
            return Double.compare(Double.parseDouble(actual), Double.parseDouble(expected));
        } catch (NumberFormatException e) {
            return actual.compareTo(expected);
        }
    }

    private static @Nullable Comparator<Setting<?>> comparator(Sort sort) {
        Comparator<Setting<?>> comparator = null;
        for (Sort.Order order : sort) {
            Comparator<Setting<?>> next = Comparator.comparing(
                    (Setting<?> setting) -> property(setting, order.getProperty()),
                    Comparator.nullsFirst(Comparator.<String>naturalOrder()));
            if (order.isDescending()) {
                next = next.reversed();
            }
            comparator = comparator == null ? next : comparator.thenComparing(next);
        }
        return comparator;
    }
}
