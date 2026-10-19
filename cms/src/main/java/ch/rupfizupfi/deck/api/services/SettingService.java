package ch.rupfizupfi.deck.api.services;

import ch.rupfizupfi.deck.data.Setting;
import ch.rupfizupfi.deck.data.SettingRepository;
import ch.rupfizupfi.deck.hilla.crud.PageCap;
import ch.rupfizupfi.deck.rig.RigLimits;
import com.vaadin.hilla.BrowserCallable;
import org.springframework.lang.NonNull;
import org.springframework.lang.Nullable;
import com.vaadin.hilla.crud.CrudService;
import com.vaadin.hilla.crud.filter.AndFilter;
import com.vaadin.hilla.crud.filter.Filter;
import com.vaadin.hilla.crud.filter.OrFilter;
import com.vaadin.hilla.crud.filter.PropertyStringFilter;
import com.vaadin.hilla.exception.EndpointException;
import jakarta.annotation.security.RolesAllowed;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Stream;

/** Implements {@link CrudService} directly: {@link Setting} is file-backed, not a JPA entity, so {@code CrudRepositoryService} does not apply.
 * The store is a JSON file, so {@link #list} filters, sorts and pages in memory instead of through Hilla's {@code JpaFilterConverter}. */
@BrowserCallable
// The directories decide where the server writes files; only the settings view, itself ADMIN-only, calls this.
@RolesAllowed("ADMIN")
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

    /** Refuses a key outside {@link Setting.Key}, a value of the wrong type, a directory the server must not write into, and a rig limit out of range or inconsistent with the rated capacity. */
    @Override
    public @Nullable Setting<?> save(Setting<?> value) {
        validate(value, repository);
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

    private static void validate(Setting<?> setting, SettingRepository repository) {
        Setting.Key key = Arrays.stream(Setting.Key.values())
                .filter(candidate -> candidate.getKey().equals(setting.getKey()))
                .findFirst()
                .orElseThrow(() -> new EndpointException("Unknown setting " + setting.getKey()));
        Object value = setting.getValue();
        if (!(key.getType() instanceof Class<?> type) || !type.isInstance(value)) {
            throw new EndpointException("Setting " + key.getKey() + " needs a value of type " + key.getType().getTypeName());
        }
        if (key == Setting.Key.FILE_UPLOAD || key == Setting.Key.FILE_RESULT_DATA) {
            validateDirectory(key, (String) value);
        }
        if (key == Setting.Key.RIG_RATED_CAPACITY || key == Setting.Key.RIG_MAX_SPEED
                || key == Setting.Key.RIG_SUCTION_LOAD_MARGIN) {
            validateRigLimit(key, ((Number) value).doubleValue(), repository);
        }
    }

    /**
     * Positive and below a sanity ceiling: every shut-off and speed check trusts these. The suction
     * load margin is held to the rated capacity, both ways: a margin above the stored capacity is
     * refused, and so is a capacity below the stored margin, since {@link RigLimits#read} would then
     * refuse every run. A stored partner that is no usable number is not checked against: it fails
     * at {@code read} on its own, and refusing here would lock the admin out of fixing this one.
     * See {@link RigLimits}.
     */
    static void validateRigLimit(Setting.Key key, double value, SettingRepository repository) {
        try {
            switch (key) {
                case RIG_RATED_CAPACITY -> {
                    RigLimits.requireRatedCapacity(value);
                    Double margin = storedNumber(repository, Setting.Key.RIG_SUCTION_LOAD_MARGIN);
                    if (margin != null && Double.isFinite(margin) && margin >= 0) {
                        RigLimits.requireSuctionLoadMargin(margin, value);
                    }
                }
                case RIG_MAX_SPEED -> RigLimits.requireMaxSpeed(value);
                case RIG_SUCTION_LOAD_MARGIN -> {
                    Double capacity = storedNumber(repository, Setting.Key.RIG_RATED_CAPACITY);
                    boolean usable = capacity != null && Double.isFinite(capacity) && capacity > 0
                            && capacity <= RigLimits.CAPACITY_UPPER_BOUND_KILONEWTON;
                    RigLimits.requireSuctionLoadMargin(value,
                            usable ? capacity : RigLimits.CAPACITY_UPPER_BOUND_KILONEWTON);
                }
                default -> throw new IllegalArgumentException("is no rig limit");
            }
        } catch (IllegalArgumentException e) {
            throw new EndpointException("Setting " + key.getKey() + ": " + e.getMessage());
        }
    }

    /** The stored (or default) value of {@code key} as a number; null when there is none or it is no number. */
    private static @Nullable Double storedNumber(SettingRepository repository, Setting.Key key) {
        Setting<?> stored = repository.getSetting(key.getKey());
        return stored != null && stored.getValue() instanceof Number number ? number.doubleValue() : null;
    }

    /** Absolute and normalized after {@code ~} expansion (as {@code StorageLocationService} resolves it), and outside the application's own directories. */
    static void validateDirectory(Setting.Key key, String value) {
        Path path;
        try {
            path = Paths.get(value.replace("~", System.getProperty("user.home")));
        } catch (InvalidPathException e) {
            throw new EndpointException("Setting " + key.getKey() + " is no valid path: " + value);
        }
        if (!path.isAbsolute() || !path.equals(path.normalize())) {
            throw new EndpointException("Setting " + key.getKey() + " must be an absolute path without . or .. segments: " + value);
        }
        for (Path protectedRoot : protectedRoots()) {
            if (path.startsWith(protectedRoot)) {
                throw new EndpointException("Setting " + key.getKey() + " must not point into " + protectedRoot);
            }
        }
    }

    /**
     * The working directory, the driver plugin directories ({@code loader.path} / {@code LOADER_PATH})
     * and every classpath directory: an upload must never land beside code the JVM loads. A
     * filesystem root is skipped — a container's working directory is {@code /}, and protecting it
     * would refuse every path.
     */
    private static List<Path> protectedRoots() {
        List<String> entries = new ArrayList<>();
        entries.add(System.getProperty("user.dir"));
        entries.add(Paths.get(System.getProperty("user.dir"), "drivers").toString());
        Optional.ofNullable(System.getProperty("loader.path")).ifPresent(paths -> entries.addAll(Arrays.asList(paths.split(","))));
        Optional.ofNullable(System.getenv("LOADER_PATH")).ifPresent(paths -> entries.addAll(Arrays.asList(paths.split(","))));
        Optional.ofNullable(System.getProperty("java.class.path")).ifPresent(paths -> entries.addAll(Arrays.asList(paths.split(File.pathSeparator))));

        List<Path> roots = new ArrayList<>();
        for (String entry : entries) {
            if (entry == null || entry.isBlank()) {
                continue;
            }
            try {
                Path root = Paths.get(entry.trim()).toAbsolutePath().normalize();
                if (root.getFileName() != null && root.getFileName().toString().endsWith(".jar")) {
                    root = root.getParent();
                }
                if (root != null && root.getParent() != null) {
                    roots.add(root);
                }
            } catch (InvalidPathException ignored) {
                // A malformed entry names no directory to protect.
            }
        }
        return roots;
    }

    @Override
    public @NonNull List<Setting<?>> list(Pageable pageable, @Nullable Filter filter) {
        pageable = PageCap.clamp(pageable);
        Stream<Setting<?>> settings = repository.getAllSettings().stream()
                .filter(setting -> matches(setting, filter));
        Comparator<Setting<?>> order = comparator(pageable.getSort());
        if (order != null) {
            settings = settings.sorted(order);
        }
        settings = settings.skip(pageable.getOffset()).limit(pageable.getPageSize());
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
