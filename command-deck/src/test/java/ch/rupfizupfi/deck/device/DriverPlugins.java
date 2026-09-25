package ch.rupfizupfi.deck.device;

import ch.rupfizupfi.deck.device.api.DriveProvider;
import ch.rupfizupfi.deck.device.api.LoadCellStreamProvider;
import org.springframework.context.annotation.Bean;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import java.util.stream.Stream;

/**
 * Reads what the driver plugin jars in {@code drivers/} declare, so the tests can report which jars
 * they checked and skip with a reason when there are none.
 *
 * <p>Reads the jars, and deliberately names no class inside one. A plugin announces itself the way
 * Spring Boot requires — an {@code AutoConfiguration.imports} entry — and announces which contract
 * it serves through the {@code @Bean} return types on the class that entry points at. Both are read
 * here from the jar and from {@code device-api}'s own types, which is what keeps this module free of
 * any compile-time knowledge of a vendor driver while still being able to verify one.
 */
final class DriverPlugins {

    /** Where Spring Boot expects a jar to declare its auto-configurations. */
    private static final String IMPORTS_ENTRY =
            "META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports";

    /** The contract types a deck plugin exists to supply. */
    private static final Set<Class<?>> PROVIDER_TYPES =
            Set.of(DriveProvider.class, LoadCellStreamProvider.class);

    private DriverPlugins() {
    }

    /**
     * The plugin directory, handed over by the {@code driverPluginTest} task. The fallback is only
     * for running a single test straight from an IDE, whose working directory is the module.
     */
    static Path directory() {
        String configured = System.getProperty("deck.driverPluginDir");
        return configured != null ? Paths.get(configured) : Paths.get("..", "drivers");
    }

    static List<Path> jars() {
        Path dir = directory();
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> entries = Files.list(dir)) {
            return entries.filter(path -> path.getFileName().toString().endsWith(".jar"))
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException("cannot list the driver plugin directory " + dir, e);
        }
    }

    /**
     * The auto-configuration classes a jar declares, loaded from the test classpath — which the
     * {@code driverPluginTest} task extends with exactly these jars, standing in for
     * {@code loader.path}.
     *
     * @throws AssertionError if the jar declares a class that is not on the classpath, which would
     *                        mean the task and the jar disagree about what is being tested
     */
    static List<Class<?>> autoConfigurationClasses(Path jar) {
        List<Class<?>> classes = new ArrayList<>();
        try (JarFile jarFile = new JarFile(jar.toFile())) {
            JarEntry entry = jarFile.getJarEntry(IMPORTS_ENTRY);
            if (entry == null) {
                return List.of();
            }
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(jarFile.getInputStream(entry), StandardCharsets.UTF_8))) {
                for (String line : reader.lines().toList()) {
                    String name = line.trim();
                    if (name.isEmpty() || name.startsWith("#")) {
                        continue;
                    }
                    try {
                        classes.add(Class.forName(name));
                    } catch (ClassNotFoundException e) {
                        throw new AssertionError(jar.getFileName() + " declares the auto-configuration "
                                + name + ", which is not on the test classpath. The jar in "
                                + directory() + " and the driverPluginTest classpath have diverged.", e);
                    }
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + jar, e);
        }
        return classes;
    }

    /**
     * Which contract types the given auto-configuration classes offer to register, read off their
     * {@code @Bean} method return types. This is what a plugin promises; whether it delivers is what
     * {@link DriverPluginBootTest} boots a context to find out.
     */
    static Set<Class<?>> declaredProviderTypes(List<Class<?>> autoConfigurations) {
        Set<Class<?>> declared = new LinkedHashSet<>();
        for (Class<?> autoConfiguration : autoConfigurations) {
            for (var method : autoConfiguration.getDeclaredMethods()) {
                if (method.isAnnotationPresent(Bean.class) && PROVIDER_TYPES.contains(method.getReturnType())) {
                    declared.add(method.getReturnType());
                }
            }
        }
        return declared;
    }

    /** Every contract type the jars present in {@code drivers/} together offer. */
    static Set<Class<?>> declaredProviderTypesAcrossAllJars() {
        Set<Class<?>> declared = new LinkedHashSet<>();
        for (Path jar : jars()) {
            declared.addAll(declaredProviderTypes(autoConfigurationClasses(jar)));
        }
        return declared;
    }

    static Set<Class<?>> requiredProviderTypes() {
        return PROVIDER_TYPES;
    }

    /**
     * The jar's main-attribute {@code Implementation-Version}, or null when the jar carries no
     * manifest or no such attribute. It is the only thing in a plugin jar that names the build it
     * came from.
     */
    static String implementationVersion(Path jar) {
        try (JarFile jarFile = new JarFile(jar.toFile())) {
            Manifest manifest = jarFile.getManifest();
            return manifest == null
                    ? null
                    : manifest.getMainAttributes().getValue("Implementation-Version");
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + jar, e);
        }
    }

    /**
     * Class entries carried by two or more of the jars with differing bytes, each mapped to the CRC
     * every jar holding it declares. {@code META-INF/} is skipped: a multi-release jar keeps a
     * per-library {@code module-info} there, and those differ by design.
     */
    static Map<String, Map<Path, Long>> conflictingSharedClasses(List<Path> jars) {
        Map<String, Map<Path, Long>> byEntry = new LinkedHashMap<>();
        for (Path jar : jars) {
            try (JarFile jarFile = new JarFile(jar.toFile())) {
                jarFile.stream()
                        .filter(entry -> entry.getName().endsWith(".class")
                                && !entry.getName().startsWith("META-INF/"))
                        .forEach(entry -> byEntry
                                .computeIfAbsent(entry.getName(), name -> new LinkedHashMap<>())
                                .put(jar, entry.getCrc()));
            } catch (IOException e) {
                throw new UncheckedIOException("cannot read " + jar, e);
            }
        }

        Map<String, Map<Path, Long>> conflicts = new LinkedHashMap<>();
        byEntry.forEach((name, crcs) -> {
            if (crcs.size() > 1 && Set.copyOf(crcs.values()).size() > 1) {
                conflicts.put(name, crcs);
            }
        });
        return conflicts;
    }

    /**
     * Whether any class in the jar calls {@code DeviceApi.verifyPluginBuiltAgainst} — that is,
     * whether the plugin performs the contract-skew check at all.
     *
     * <p>Without this, verifying a plugin is circular. The skew check lives in the plugin's own
     * initializer by design (the version it was built against exists only as a constant javac
     * inlined into its class file), so a jar that never calls it loads clean and proves nothing,
     * and a test that merely instantiates its auto-configuration reports a pass it did not earn.
     *
     * <p>A constant-pool scan of the raw class bytes rather than reflection: the method name appears
     * as a UTF8 entry in every class that references it, which is exactly the question being asked,
     * and it needs no bytecode library. Every class in the jar is searched, not just the
     * auto-configuration, because the contract only asks that the plugin make the call — not where.
     */
    static boolean verifiesItselfAgainstTheContract(Path jar) {
        byte[] needle = "verifyPluginBuiltAgainst".getBytes(StandardCharsets.UTF_8);
        try (JarFile jarFile = new JarFile(jar.toFile())) {
            return jarFile.stream()
                    .filter(entry -> entry.getName().endsWith(".class"))
                    .anyMatch(entry -> containsBytes(jarFile, entry, needle));
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + jar, e);
        }
    }

    private static boolean containsBytes(JarFile jarFile, JarEntry entry, byte[] needle) {
        try (var in = jarFile.getInputStream(entry)) {
            byte[] bytes = in.readAllBytes();
            for (int i = 0; i <= bytes.length - needle.length; i++) {
                int j = 0;
                while (j < needle.length && bytes[i + j] == needle[j]) {
                    j++;
                }
                if (j == needle.length) {
                    return true;
                }
            }
            return false;
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + entry.getName() + " in " + jarFile.getName(), e);
        }
    }
}
