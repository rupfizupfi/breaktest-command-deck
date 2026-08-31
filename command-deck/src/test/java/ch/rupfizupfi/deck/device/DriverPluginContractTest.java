package ch.rupfizupfi.deck.device;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.abort;

/**
 * Checks each driver plugin jar in {@code drivers/} against the contract it will actually run on.
 *
 * <p>Nothing else can catch what this catches. The driver repos compile against the live
 * {@code device-api} — that is their conformance check — but the jar sitting in {@code drivers/} is
 * whatever was last copied there, possibly built months and one contract bump ago. Instantiating a
 * plugin's auto-configuration class runs its initializer, which is where the plugin calls
 * {@code DeviceApi.verifyPluginBuiltAgainst(ContractVersion.VALUE)} with the version javac inlined
 * into <em>its</em> class file. So this compares the jar's build-time contract against this build's
 * contract, which is the one comparison that matters and the one no compiler can make.
 *
 * <p>No Spring context and no hardware: instantiating the auto-configuration class does not invoke
 * its {@code @Bean} methods, and it is those methods — not the class — that open a USB device. This
 * runs per jar, so it is useful with only one driver present, which is the normal state of a dev
 * machine.
 */
@Tag("driver-plugin")
class DriverPluginContractTest {

    @Test
    void everyPluginJarPerformsTheContractSkewCheck() {
        List<Path> jars = requirePluginJars();

        for (Path jar : jars) {
            // Asserted before the check's *result* is trusted below, because a jar that never makes
            // the call cannot fail it. That is the one way this whole suite could report a pass it
            // did not earn, and it is not hypothetical: a jar built before the check existed loads
            // perfectly and verifies nothing.
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

            // A jar on loader.path that registers no provider is indistinguishable at startup from
            // no jar at all: HardwareModeCheck names a missing provider and the operator goes
            // looking for a file that is right there. Named here instead.
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
     * The initializer's failure, or null when the plugin is compatible. Unwrapped from the
     * reflection wrapper so the assertion above reports the driver's own message.
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
