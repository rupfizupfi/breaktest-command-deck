package ch.rupfizupfi.deck.device;

import ch.rupfizupfi.deck.Application;
import ch.rupfizupfi.deck.device.api.DeviceApi;
import ch.rupfizupfi.deck.device.api.DriveProvider;
import ch.rupfizupfi.deck.device.api.LoadCellStreamProvider;
import ch.rupfizupfi.deck.device.simulated.SimulatedDriveProvider;
import ch.rupfizupfi.deck.device.simulated.SimulatedLoadCellStreamProvider;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.util.ClassUtils;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.abort;

/**
 * Boots the real command-deck context against the real driver plugins and asserts they take over
 * from the simulators.
 *
 * <p>{@link DriverPluginContractTest} checks each jar; this checks the assembly: auto-configurations
 * are discovered, their conditions agree with {@code deck.hardware.mode=real}, and
 * {@code HardwareModeCheck} and {@code PluginContractCheck} accept them.
 *
 * <p>Windows only: both drivers refuse to register elsewhere (doc/dev/backend/driver-jars.md).
 *
 * <p>Touches no hardware. Nothing calls a provider's {@code open()}: the only startup path that
 * reaches the drive, {@code StartupRecoveryRunner}'s boot de-energize, runs on every real-mode boot
 * and is switched off here by {@code deck.startup.de-energize=false} - that property is the safety
 * interlock, do not drop it. On context close {@code ShutdownSafeStop} finds no open drive handle and
 * only latches. The fresh in-memory database holds only the migrations' reference data, and
 * {@code deck.dev.seed-users=false} keeps even the accounts out.
 *
 * <p>The inlined properties override {@code application-dev.properties}'s {@code simulated}.
 * {@code classes} is explicit because both modules declare {@code ch.rupfizupfi.deck.Application}.
 */
@SpringBootTest(classes = Application.class, properties = {
        "deck.hardware.mode=real",
        "deck.startup.de-energize=false",
        "spring.datasource.url=jdbc:h2:mem:driver-plugin-boot-test;DB_CLOSE_DELAY=-1;MODE=PostgreSQL;DEFAULT_NULL_ORDERING=HIGH",
        "deck.dev.seed-users=false",
        "vaadin.launch-browser=false"
})
@ActiveProfiles("dev")
@EnabledOnOs(OS.WINDOWS)
@Tag("driver-plugin")
class DriverPluginBootTest {

    @Autowired
    ApplicationContext context;

    /**
     * Runs before the context is created, so an incomplete set of plugins skips rather than fails:
     * one driver on a dev machine is normal, and {@code HardwareModeCheck} requires both.
     */
    @BeforeAll
    static void requireEveryProviderToBeCovered() {
        Set<Class<?>> declared = DriverPlugins.declaredProviderTypesAcrossAllJars();
        Set<Class<?>> required = DriverPlugins.requiredProviderTypes();

        if (!declared.containsAll(required)) {
            var missing = required.stream().filter(type -> !declared.contains(type)).toList();
            abort("the driver plugins in " + DriverPlugins.directory() + " (" + DriverPlugins.jars().size()
                    + " jar(s)) cover " + declared + " but not " + missing
                    + ". A real boot needs every provider, so there is nothing to boot here yet.");
        }
    }

    @Test
    void driverPluginsSupplyBothProvidersInsteadOfTheSimulators() {
        // "Not the simulator" rather than a vendor type: this module names no driver class.
        assertThat(context.getBean(DriveProvider.class))
                .isNotInstanceOf(SimulatedDriveProvider.class);
        assertThat(context.getBean(LoadCellStreamProvider.class))
                .isNotInstanceOf(SimulatedLoadCellStreamProvider.class);
    }

    @Test
    void theSimulatedProvidersAreAbsentEntirely() {
        // Driver and simulated providers are conditional on opposite values of the same property,
        // so exactly one bean of each type may exist.
        assertThat(context.getBeanNamesForType(DriveProvider.class)).hasSize(1);
        assertThat(context.getBeanNamesForType(LoadCellStreamProvider.class)).hasSize(1);
    }

    @Test
    void eachProviderIsVouchedForByTheAutoConfigurationThatDeclaresIt() {
        // PluginContractCheck matched each provider to a reported skew check; this pins that the
        // match went through the @Bean method's class, where both drivers make the call.
        var beanFactory = ((ConfigurableApplicationContext) context).getBeanFactory();
        for (Class<?> providerType : List.of(DriveProvider.class, LoadCellStreamProvider.class)) {
            String beanName = context.getBeanNamesForType(providerType)[0];
            var definition = (RootBeanDefinition) beanFactory.getMergedBeanDefinition(beanName);
            Class<?> autoConfiguration = ClassUtils.getUserClass(definition.getResolvedFactoryMethod().getDeclaringClass());

            assertThat(DeviceApi.reportedBuiltAgainst())
                    .as("%s bean '%s' from %s", providerType.getSimpleName(), beanName, autoConfiguration.getName())
                    .containsKey(autoConfiguration);
        }
    }

    @Test
    void theBootLeavesTheDriveUntouched() {
        // The interlock above: with the de-energize off, nothing opened a drive handle at boot.
        assertThat(context.getBean(DeviceService.class).getFrequencyInverter().isDriveHandleOpen()).isFalse();
    }

    @Test
    void theAnnouncedModeIsReal() {
        // What the UI reports to the operator.
        assertThat(context.getBean(HardwareModeInfo.class).isSimulated()).isFalse();
    }
}
