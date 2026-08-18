package ch.rupfizupfi.deck.device;

import ch.rupfizupfi.deck.device.api.DriveProvider;
import ch.rupfizupfi.deck.device.api.LoadCellStreamProvider;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

import java.util.ArrayList;

/**
 * Refuses to start when the selected {@link HardwareMode} cannot be served.
 * <p>
 * A {@code BeanFactoryPostProcessor} rather than an ordinary bean, and that is the whole point: it
 * runs after the bean definitions are known but before any singleton is instantiated, so it reports
 * the missing drivers itself instead of letting {@code DeviceService}'s constructor surface them as
 * a {@code NoSuchBeanDefinitionException} that names an interface and not a jar.
 * <p>
 * Never falls back. Absent hardware selecting a simulator is the failure mode this exists to make
 * impossible — see {@code doc/_research/machine-safety.md}.
 */
@Component
public class HardwareModeCheck implements BeanFactoryPostProcessor {

    /** The on-machine deployment profile; it is wired to a real bench and may never simulate. */
    private static final String PRODUCTION_PROFILE = "docker";

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
                    "DriveProvider (frequency converter) - lib/usbmodbus.jar",
                    "LoadCellStreamProvider (load cell) - lib/dscusb.jar",
                    "The vendor driver jars are not on the classpath, so the optional 'drivers' "
                            + "source set was not compiled. Put dscusb.jar AND usbmodbus.jar in "
                            + "lib/ and rebuild. Provenance and build requirements for both jars: "
                            + "doc/03-backend/driver-jars.md. This never falls back to a simulator "
                            + "- a test bench that cannot reach its hardware must not run at all.");
            case SIMULATED -> {
                refuseSimulationInProduction(environment);
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
     * Simulated hardware must never reach the machine. The deployment profile is the boundary the
     * decision was taken on, so refusing here beats trusting a property nobody re-checks — and this
     * runs before the datasource, so the refusal never depends on a reachable database.
     */
    private void refuseSimulationInProduction(Environment environment) {
        if (!environment.acceptsProfiles(Profiles.of(PRODUCTION_PROFILE))) {
            return;
        }

        throw new IllegalStateException(message(
                HardwareMode.PROPERTY + "=simulated with the '" + PRODUCTION_PROFILE
                        + "' profile active. That profile is the on-machine deployment, wired to a "
                        + "real test bench.",
                "Simulated hardware is a development facility and must never run against the "
                        + "machine. Either drop the '" + PRODUCTION_PROFILE + "' profile, or set "
                        + HardwareMode.PROPERTY + "=real. This is refused rather than ignored: a "
                        + "silently downgraded mode would make a simulated run indistinguishable "
                        + "from a real one."));
    }

    /**
     * True inside a context booted by the build rather than by an operator. {@code hillaGenerate}
     * starts a Spring AOT context purely to discover {@code @BrowserCallable} classes, and that
     * context is not going to drive anything - refusing to start it would make the build itself
     * depend on the vendor jars, which is exactly what the optional source set removes.
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
