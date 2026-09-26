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
 * A {@code BeanFactoryPostProcessor} rather than an ordinary bean, and that is the whole point: it
 * runs after the bean definitions are known but before any singleton is instantiated, so it reports
 * the missing drivers itself instead of letting {@code DeviceService}'s constructor surface them as
 * a {@code NoSuchBeanDefinitionException} that names an interface and not a jar.
 * <p>
 * Never falls back. Absent hardware selecting a simulator is the failure mode this exists to make
 * impossible.
 */
@Component
public class HardwareModeCheck implements BeanFactoryPostProcessor {

    /**
     * The only profiles a simulator may run under — an allowlist, not a blocklist.
     * <p>
     * Blocklisting the deployment profile was the earlier shape and it had the failure mode
     * backwards: any profile nobody remembered to add was permitted to simulate. An allowlist fails
     * safe for profiles that do not exist yet, and enabling one becomes a reviewed edit here.
     * <p>
     * {@code docker} is on it because the deck container is a <b>simulation and test deployment</b>,
     * not a bench: it runs a Linux image, where both drivers refuse to register on principle, so it
     * could not reach hardware even with a jar. What keeps that from also permitting a simulator on
     * the real bench is {@code containsAll} below plus the {@code bench} profile: a native bench run
     * activates {@code bench}, which groups to {@code docker}, so its active set is
     * {@code [bench, docker]} — and {@code bench} is deliberately <b>not</b> on this list, so
     * simulation there is refused. That makes {@code bench} the marker for "this JVM may reach the
     * machine", and {@code script/run-bench.ps1} always sets it. Starting a bench natively with
     * {@code SPRING_PROFILES_ACTIVE=docker} instead would bypass that, which is why the script is
     * the documented way in ({@code doc/05-ops/bench-deployment.md}).
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
                            + "requirements are in doc/03-backend/driver-jars.md. If this is a "
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
     * Simulated hardware must never reach the machine. Every active profile must be one that
     * permits simulation, so adding <em>any</em> other profile refuses — including alongside
     * {@code dev}, which a blocklist on the deployment profile got wrong in the other direction.
     * <p>
     * Runs before the datasource, so the refusal never depends on a reachable database.
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
     * {@code getActiveProfiles()} returns empty rather than the defaults when nothing is set, so the
     * fallback has to be explicit — without it an unconfigured boot would present an empty set,
     * which any allowlist trivially contains, and the guard would pass on exactly the configuration
     * it is most likely to meet.
     */
    private static Set<String> effectiveProfiles(Environment environment) {
        String[] active = environment.getActiveProfiles();
        String[] effective = active.length > 0 ? active : environment.getDefaultProfiles();
        return new LinkedHashSet<>(Arrays.asList(effective));
    }

    /**
     * True inside a context booted by the build rather than by an operator. {@code hillaGenerate}
     * starts a Spring AOT context purely to discover {@code @BrowserCallable} classes, and that
     * context is not going to drive anything.
     * <p>
     * Load-bearing for every build, not just a driverless one: drivers now arrive at launch over
     * loader.path, so a build context never has a provider bean and the default mode is real.
     * Without this exemption the frontend client could not be generated without the vendor jars.
     */
    private static boolean isBuildTimeContext() {
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

    private static String message(String problem, String action) {
        return System.lineSeparator()
                + System.lineSeparator() + "*** COMMAND DECK CANNOT START ***"
                + System.lineSeparator()
                + System.lineSeparator() + "Problem: " + problem
                + System.lineSeparator()
                + System.lineSeparator() + "Action:  " + action
                + System.lineSeparator();
    }
}
