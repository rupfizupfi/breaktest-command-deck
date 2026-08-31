package ch.rupfizupfi.deck.device;

import ch.rupfizupfi.deck.Application;
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
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.abort;

/**
 * Boots the real command-deck context against the real driver plugins and asserts they take over
 * from the simulators.
 *
 * <p>{@link DriverPluginContractTest} proves each jar is compatible; this proves the assembly
 * works — that the plugins' auto-configurations are discovered from the classpath, that their
 * conditions agree with {@code deck.hardware.mode=real}, and that {@code HardwareModeCheck} is
 * satisfied by them rather than refusing the boot. It is the closest thing to a bench launch that
 * runs without a bench.
 *
 * <p>Windows only, and that is the subject matter rather than a limitation: both drivers gate
 * themselves on the platform their vendor libraries exist for, so off Windows they deliberately do
 * not register and this boot would correctly fail (doc/03-backend/driver-jars.md).
 *
 * <p><b>Touches no hardware.</b> Two independent reasons, both load-bearing on a machine wired to a
 * destructive test rig. A provider bean only opens a device when something calls {@code open()}, and
 * nothing here does — {@code DeviceService}'s constructor just stores the providers. The one startup
 * path that would reach the drive is {@code StartupRecoveryRunner}'s de-energize, which fires only
 * when the sweep finds a non-terminal run; {@code spring.sql.init.mode=never} below leaves the
 * throwaway database empty, so there is no row to find. That property is a safety interlock, not a
 * convenience — do not drop it to get seed data.
 *
 * <p>The inlined properties beat {@code application-dev.properties}, which is what lets profile
 * {@code dev} — the only profile {@code HardwareModeCheck} permits a simulator under, and therefore
 * the profile whose promise of {@code simulated} this test has to override — ask for real hardware.
 * {@code classes} is explicit for the same reason as in {@code ApplicationContextTest}: both modules
 * declare {@code ch.rupfizupfi.deck.Application}.
 */
@SpringBootTest(classes = Application.class, properties = {
        "deck.hardware.mode=real",
        "spring.datasource.url=jdbc:h2:mem:driver-plugin-boot-test;DB_CLOSE_DELAY=-1",
        "spring.sql.init.mode=never",
        "vaadin.launch-browser=false"
})
@ActiveProfiles("dev")
@EnabledOnOs(OS.WINDOWS)
@Tag("driver-plugin")
class DriverPluginBootTest {

    @Autowired
    ApplicationContext context;

    /**
     * Runs before the context is created, so an incomplete set of plugins skips rather than failing:
     * {@code HardwareModeCheck} requires both providers, correctly, and one driver on a dev machine
     * is the normal state. What is missing is named, so a skip is still a useful report.
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
        // Asserted as "not the simulator" rather than against a vendor type: this module names no
        // driver class, and the property that matters is precisely that the simulated pair lost.
        assertThat(context.getBean(DriveProvider.class))
                .isNotInstanceOf(SimulatedDriveProvider.class);
        assertThat(context.getBean(LoadCellStreamProvider.class))
                .isNotInstanceOf(SimulatedLoadCellStreamProvider.class);
    }

    @Test
    void theSimulatedProvidersAreAbsentEntirely() {
        // Not merely outvoted - absent. Both driver auto-configurations and both simulated providers
        // are conditional on the same property with opposite values, so a single DriveProvider bean
        // is what proves the two can never both register. Two would also mean DeviceService's
        // constructor injection was one ambiguity away from failing.
        assertThat(context.getBeanNamesForType(DriveProvider.class)).hasSize(1);
        assertThat(context.getBeanNamesForType(LoadCellStreamProvider.class)).hasSize(1);
    }

    @Test
    void theAnnouncedModeIsReal() {
        // What the UI reports to the operator. A run recorded as real must have been real.
        assertThat(context.getBean(HardwareModeInfo.class).isSimulated()).isFalse();
    }
}
