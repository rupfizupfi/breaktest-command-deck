package ch.rupfizupfi.deck.device;

import ch.rupfizupfi.deck.device.api.DriveProvider;
import ch.rupfizupfi.deck.device.api.LoadCellStreamProvider;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Refuses to start when the selected {@link HardwareMode} cannot be served.
 * <p>
 * A {@code BeanFactoryPostProcessor} so it runs before any singleton is instantiated and can name
 * the missing jar, instead of a {@code NoSuchBeanDefinitionException} naming an interface.
 * <p>
 * Never falls back to a simulator.
 * <p>
 * It sees bean definitions only, so it cannot tell whether a provider's plugin checked itself
 * against the contract; {@link PluginContractCheck} does that once the provider exists.
 */
@Component
public class HardwareModeCheck implements BeanFactoryPostProcessor {

    /**
     * The only profiles a simulator may run under; an allowlist, so an unknown profile fails safe.
     * <p>
     * {@code docker} is on it because the deck container is the simulation and test deployment: a
     * Linux image, where both drivers refuse to register. A native bench run activates {@code bench}
     * (grouped to {@code docker}), and {@code bench} is not on this list, so {@code containsAll}
     * refuses simulation there. {@code script/run-bench.ps1} always sets it; starting a bench with
     * only {@code SPRING_PROFILES_ACTIVE=docker} would bypass the check
     * ({@code doc/ops/bench-deployment.md}).
     */
    private static final Set<String> SIMULATION_PROFILES = Set.of("dev", "docker");

    @Override
    public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) throws BeansException {
        if (isBuildTimeContext()) {
            return;
        }

        // The Environment is a pre-registered singleton, so asking for it here instantiates nothing.
        Environment environment = beanFactory.getBean(Environment.class);
        String configured = environment.getProperty(HardwareMode.PROPERTY, HardwareMode.DEFAULT.propertyValue());

        HardwareMode mode = HardwareMode.parse(configured);
        if (mode == null) {
            throw new IllegalStateException(HardwareMode.PROPERTY + "=" + configured
                    + " is not a valid hardware mode. Valid values: real, simulated.");
        }

        switch (mode) {
            case REAL -> requireProviders(beanFactory, mode,
                    "DriveProvider (frequency inverter) - usbmodbus.jar",
                    "LoadCellStreamProvider (load cell) - dscusb.jar",
                    "No driver plugin registered the provider. Drivers are loaded at launch "
                            + "from the loader.path directories (LOADER_PATH env var, or "
                            + "-Dloader.path), not from the build - so put the jar in place and "
                            + "restart, there is nothing to rebuild. Every path on a Windows "
                            + "machine reads drivers/: put both jars there, then either "
                            + "script/run-bench.ps1 (which sets LOADER_PATH for you) or bootRun "
                            + "with --deck.hardware.mode=real. Check the jars themselves with "
                            + "`gradlew :command-deck:driverPluginTest`; provenance and build "
                            + "requirements are in doc/dev/backend/driver-jars.md. If this is a "
                            + "container: it cannot drive the bench at all - both drivers are "
                            + "Windows-only - so it is meant to run "
                            + "deck.hardware.mode=simulated, and real mode here is a "
                            + "misconfiguration rather than a missing file. This never falls back "
                            + "to a simulator: a test bench that cannot reach its hardware must "
                            + "not run at all.");
            case SIMULATED -> {
                requireSimulationProfile(environment);
                requireProviders(beanFactory, mode,
                        "SimulatedDriveProvider", "SimulatedLoadCellStreamProvider",
                        "The simulated providers did not register. They are conditional on "
                                + HardwareMode.PROPERTY + "=simulated resolving in the same "
                                + "Environment this check reads, so a property source that is "
                                + "applied later cannot select them.");
            }
        }
    }

    /**
     * Simulated hardware must never reach the machine: every active profile must permit simulation,
     * so any other profile refuses, even alongside {@code dev}. Runs before the datasource.
     */
    private void requireSimulationProfile(Environment environment) {
        Set<String> active = effectiveProfiles(environment);
        if (SIMULATION_PROFILES.containsAll(active)) {
            return;
        }

        throw new IllegalStateException(message(
                HardwareMode.PROPERTY + "=simulated with profiles " + active + " active. "
                        + "Simulation is permitted only under " + SIMULATION_PROFILES + ".",
                "Simulated hardware is a development facility and must never run against the "
                        + "machine, and any profile beyond the permitted set may be a deployment. "
                        + "Either run with only " + SIMULATION_PROFILES + " active, or set "
                        + HardwareMode.PROPERTY + "=real. This is refused rather than ignored: a "
                        + "silently downgraded mode would make a simulated run indistinguishable "
                        + "from a real one. If a new profile legitimately needs a simulator, add it "
                        + "to SIMULATION_PROFILES deliberately."));
    }

    /**
     * {@code getActiveProfiles()} returns empty rather than the defaults when nothing is set, and any
     * allowlist contains an empty set, so the fallback must be explicit.
     */
    private static Set<String> effectiveProfiles(Environment environment) {
        String[] active = environment.getActiveProfiles();
        String[] effective = active.length > 0 ? active : environment.getDefaultProfiles();
        return new LinkedHashSet<>(Arrays.asList(effective));
    }

    /**
     * True inside a context booted by the build rather than by an operator. {@code hillaGenerate}
     * starts a Spring AOT context to discover {@code @BrowserCallable} classes. Drivers arrive at
     * launch over loader.path, so without this exemption no build could generate the frontend client.
     */
    static boolean isBuildTimeContext() {
        return org.springframework.aot.AotDetector.useGeneratedArtifacts()
                || Boolean.getBoolean("spring.aot.processing");
    }

    /**
     * allowEagerInit is false throughout: a provider's constructor may already touch the USB stack,
     * and this check must not be the thing that opens a device.
     */
    private void requireProviders(ConfigurableListableBeanFactory beanFactory, HardwareMode mode,
                                  String driveLabel, String streamLabel, String action) {
        var missing = new ArrayList<String>();
        if (beanFactory.getBeanNamesForType(DriveProvider.class, true, false).length == 0) {
            missing.add(driveLabel);
        }
        if (beanFactory.getBeanNamesForType(LoadCellStreamProvider.class, true, false).length == 0) {
            missing.add(streamLabel);
        }

        if (!missing.isEmpty()) {
            throw new IllegalStateException(message(
                    HardwareMode.PROPERTY + "=" + mode.propertyValue()
                            + ", but the hardware providers are missing: "
                            + String.join("; ", missing) + ".",
                    action));
        }
    }

    /** The startup-refusal format, shared with {@link PluginContractCheck}. */
    static String message(String problem, String action) {
        return System.lineSeparator()
                + System.lineSeparator() + "*** COMMAND DECK CANNOT START ***"
                + System.lineSeparator()
                + System.lineSeparator() + "Problem: " + problem
                + System.lineSeparator()
                + System.lineSeparator() + "Action:  " + action
                + System.lineSeparator();
    }
}
