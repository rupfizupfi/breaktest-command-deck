package ch.rupfizupfi.deck.device;

import ch.rupfizupfi.deck.device.api.DriveProvider;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.InvocationTargetException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.abort;

/**
 * Checks each driver plugin jar in {@code drivers/} against the contract it will actually run on,
 * and the jars against each other.
 *
 * <p>The jar in {@code drivers/} may predate a contract bump. Instantiating a plugin's
 * auto-configuration runs its initializer, which calls
 * {@code DeviceApi.verifyPluginBuiltAgainst(ContractVersion.VALUE)} with the version inlined at the
 * jar's build, so this compares that against this build's contract.
 *
 * <p>No Spring context and no hardware: instantiation does not invoke the {@code @Bean} methods,
 * which are what open a USB device. Runs per jar, so one driver is enough.
 */
@Tag("driver-plugin")
class DriverPluginContractTest {

    private static final Logger logger = LoggerFactory.getLogger(DriverPluginContractTest.class);

    @Test
    void everyPluginJarPerformsTheContractSkewCheck() {
        List<Path> jars = requirePluginJars();

        for (Path jar : jars) {
            // A jar that never makes the call cannot fail it, so the skew check's result means
            // nothing without this.
            assertThat(DriverPlugins.verifiesItselfAgainstTheContract(jar))
                    .as("%s never calls DeviceApi.verifyPluginBuiltAgainst, so nothing checks it "
                            + "against the contract it is running on and a skewed jar would reach "
                            + "the bench unnoticed. The jar predates the check or was built without "
                            + "it. Rebuild it from its own repo (`./gradlew shadowJar` in ../dscusb "
                            + "or ../usbmodbus) and copy the result into %s.",
                            jar.getFileName(), DriverPlugins.directory())
                    .isTrue();
        }
    }

    @Test
    void everyPluginJarSatisfiesTheRunningContract() {
        List<Path> jars = requirePluginJars();

        for (Path jar : jars) {
            List<Class<?>> autoConfigurations = DriverPlugins.autoConfigurationClasses(jar);

            assertThat(autoConfigurations)
                    .as("%s declares no auto-configuration, so nothing in it can ever register a "
                            + "provider. A deck plugin announces itself through "
                            + "META-INF/spring/...AutoConfiguration.imports - this jar is not one, "
                            + "or was built without that file.", jar.getFileName())
                    .isNotEmpty();

            for (Class<?> autoConfiguration : autoConfigurations) {
                assertThat(instantiationFailure(autoConfiguration))
                        .as("%s in %s was built against a device-api version this build cannot "
                                + "serve. Rebuild the driver against the current contract and copy "
                                + "its jar back into %s - a file drop, no application rebuild. "
                                + "The message below is the driver's own.",
                                autoConfiguration.getName(), jar.getFileName(), DriverPlugins.directory())
                        .isNull();
            }
        }
    }

    @Test
    void everyPluginJarServesTheDeviceContract() {
        List<Path> jars = requirePluginJars();

        for (Path jar : jars) {
            Set<Class<?>> declared =
                    DriverPlugins.declaredProviderTypes(DriverPlugins.autoConfigurationClasses(jar));

            // At startup such a jar looks like a missing one; name it here instead.
            assertThat(declared)
                    .as("%s registers no device-api provider bean. Its auto-configuration declares "
                            + "no @Bean returning one of %s, so the deck would refuse to start in "
                            + "real mode naming a provider as missing while this jar sits in place.",
                            jar.getFileName(), DriverPlugins.requiredProviderTypes())
                    .isNotEmpty();
            assertThat(DriverPlugins.requiredProviderTypes()).containsAll(declared);
        }
    }

    /**
     * A drive plugin built against contract 1.1.0 passes the skew check, which allows an older
     * minor, and then fails every energize. Caught here instead of on the bench.
     */
    @Test
    void everyDrivePluginArmsTheLinkLossStop() {
        List<Path> jars = requirePluginJars();
        List<Path> drivePlugins = jars.stream()
                .filter(jar -> DriverPlugins.declaredProviderTypes(DriverPlugins.autoConfigurationClasses(jar))
                        .contains(DriveProvider.class))
                .toList();
        if (drivePlugins.isEmpty()) {
            abort("no drive plugin jar in " + DriverPlugins.directory() + " - nothing arms a link-loss stop.");
        }

        for (Path jar : drivePlugins) {
            Map<String, Boolean> drives = DriverPlugins.driveImplementations(jar);

            assertThat(drives)
                    .as("%s registers a DriveProvider but contains no concrete Drive", jar.getFileName())
                    .isNotEmpty();
            assertThat(drives)
                    .as("%s was built before Drive#enableLinkLossStop (device-api 1.2.0): it loads, but "
                                    + "the deck refuses to energize a drive that cannot stop itself on a lost "
                                    + "link. Rebuild it (`./gradlew shadowJar` in ../usbmodbus) and copy the "
                                    + "result into %s.",
                            jar.getFileName(), DriverPlugins.directory())
                    .doesNotContainValue(false);
        }
    }

    /**
     * Warns, never fails: a drive jar built before {@code Drive#readControlSource} (device-api 1.3.0)
     * answers UNKNOWN through the contract's default, and the boot de-energize treats UNKNOWN as
     * not REMOTE — the pre-1.3.0 behaviour. The jar is still safe; it only cannot spare a drive under
     * the button control board (REMOTE).
     */
    @Test
    void everyDrivePluginReportsItsControlSource() {
        List<Path> drivePlugins = requirePluginJars().stream()
                .filter(jar -> DriverPlugins.declaredProviderTypes(DriverPlugins.autoConfigurationClasses(jar))
                        .contains(DriveProvider.class))
                .toList();
        if (drivePlugins.isEmpty()) {
            abort("no drive plugin jar in " + DriverPlugins.directory() + " - nothing reports a control source.");
        }

        for (Path jar : drivePlugins) {
            Map<String, Boolean> drives = DriverPlugins.driveImplementationsOverriding(jar, "readControlSource");
            if (drives.containsValue(false)) {
                String warning = jar.getFileName() + " was built before Drive#readControlSource (device-api"
                        + " 1.3.0): every boot de-energizes the drive even under button-board (REMOTE) control. Rebuild"
                        + " it (`./gradlew shadowJar` in ../usbmodbus) and copy the result into "
                        + DriverPlugins.directory() + ".";
                logger.warn(warning);
                System.err.println("WARNING: " + warning);
            }
        }
    }

    @Test
    void everyPluginJarDeclaresItsVersion() {
        List<Path> jars = requirePluginJars();

        for (Path jar : jars) {
            assertThat(DriverPlugins.implementationVersion(jar))
                    .as("%s declares no Implementation-Version, so the jar in %s cannot be traced "
                            + "back to the build it came from. Add `manifest { attributes("
                            + "\"Implementation-Version\" to project.version.toString()) }` to the "
                            + "driver's shadowJar block, rebuild it (`./gradlew shadowJar`) and copy "
                            + "the result back.",
                            jar.getFileName(), DriverPlugins.directory())
                    .isNotBlank();
        }
    }

    @Test
    void pluginJarsAgreeOnEverySharedClass() {
        List<Path> jars = requirePluginJars();
        if (jars.size() < 2) {
            abort("only one driver plugin jar in " + DriverPlugins.directory()
                    + " - a shared class needs two to disagree.");
        }

        Map<String, Map<Path, Long>> conflicts = DriverPlugins.conflictingSharedClasses(jars);
        String firstConflict = conflicts.isEmpty() ? "" : conflicts.keySet().iterator().next();

        assertThat(conflicts)
                .as("%s carry differing copies of %s shared class(es), the first being %s. Each "
                        + "plugin bundles its own copy of the libraries it uses, Kotlin's stdlib "
                        + "above all, and PropertiesLauncher loads whichever jar it lists first - so "
                        + "one driver would run on the other's stdlib. Pin the same Kotlin version "
                        + "in both driver repos and rebuild both jars.",
                        jars, conflicts.size(), firstConflict)
                .isEmpty();
    }

    /**
     * The initializer's failure, or null when the plugin is compatible. Unwrapped so the assertion
     * reports the driver's own message.
     */
    private static Throwable instantiationFailure(Class<?> autoConfiguration) {
        try {
            autoConfiguration.getDeclaredConstructor().newInstance();
            return null;
        } catch (InvocationTargetException e) {
            return e.getCause() != null ? e.getCause() : e;
        } catch (ReflectiveOperationException e) {
            return e;
        }
    }

    private static List<Path> requirePluginJars() {
        List<Path> jars = DriverPlugins.jars();
        if (jars.isEmpty()) {
            abort("no driver plugin jar in " + DriverPlugins.directory()
                    + " - nothing to verify. Build one in ../dscusb or ../usbmodbus with "
                    + "`./gradlew shadowJar` and copy it there.");
        }
        return jars;
    }
}
