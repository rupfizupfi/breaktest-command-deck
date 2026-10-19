package ch.rupfizupfi.deck.device;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pins which deployments may run a simulator: a bench must never be driven by a simulator, and a
 * simulated run must never be mistaken for a real one.
 *
 * <p>{@code docker} (the deck container) may simulate; a native bench run activates {@code bench},
 * which groups to {@code docker}, and must not. Only the extra {@code bench} entry separates them, so
 * {@code bench} must never join the allowlist.
 *
 * <p>No provider beans are registered, so the branch that fired is read off the message: profiles
 * named means the profile gate refused, providers named means it passed.
 */
class HardwareModeCheckTest {

    @Test
    void theDeckContainerMaySimulate() {
        // SPRING_PROFILES_ACTIVE=docker, DECK_HARDWARE_MODE=simulated — docker/docker-compose.yaml.
        assertThatThrownBy(() -> check("simulated", "docker"))
                .hasMessageContaining("SimulatedDriveProvider")
                .as("the profile gate must let `docker` through, leaving only the provider check")
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain("Simulation is permitted only under"));
    }

    @Test
    void theBenchMayNotSimulate() {
        // script/run-bench.ps1 sets SPRING_PROFILES_ACTIVE=bench; the profile group makes that
        // [bench, docker].
        assertThatThrownBy(() -> check("simulated", "bench", "docker"))
                .hasMessageContaining("Simulation is permitted only under")
                .hasMessageContaining("bench");
    }

    @Test
    void anUnlistedProfileMayNotSimulate() {
        // The allowlist has to fail safe for profiles nobody has thought of yet.
        assertThatThrownBy(() -> check("simulated", "prod"))
                .hasMessageContaining("Simulation is permitted only under");
    }

    @Test
    void devMaySimulate() {
        assertThatThrownBy(() -> check("simulated", "dev"))
                .hasMessageContaining("SimulatedDriveProvider")
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain("Simulation is permitted only under"));
    }

    @Test
    void realModeIsRefusedWithoutDriversUnderEveryProfile() {
        // Never a fallback: real without providers refuses everywhere, naming the jars.
        for (String profile : new String[]{"dev", "docker", "bench", "prod"}) {
            assertThatThrownBy(() -> check("real", profile))
                    .as("profile %s", profile)
                    .hasMessageContaining("usbmodbus.jar")
                    .hasMessageContaining("dscusb.jar");
        }
    }

    @Test
    void anUnparseableModeIsRefusedRatherThanDefaulted() {
        assertThatThrownBy(() -> check("Simulated ", "dev"))
                .hasMessageContaining("is not a valid hardware mode");
    }

    @Test
    void aBuildTimeContextIsExemptEvenWithNoDrivers() {
        // hillaGenerate boots an AOT context purely to discover @BrowserCallable classes; it drives
        // nothing, and every build would otherwise need the vendor jars.
        System.setProperty("spring.aot.processing", "true");
        try {
            assertThatCode(() -> check("real", "docker")).doesNotThrowAnyException();
        } finally {
            System.clearProperty("spring.aot.processing");
        }
    }

    @Test
    void anUnconfiguredBootIsJudgedOnItsDefaultProfiles() {
        // Nothing active means the default set, [default]; an empty active set must not slip
        // through the allowlist.
        assertThatThrownBy(() -> check("simulated", new String[0]))
                .hasMessageContaining("Simulation is permitted only under")
                .hasMessageContaining("[default]");
    }

    @Test
    void aDefaultDevProfileMaySimulate() {
        // spring.profiles.default=dev counts like an active dev profile.
        assertThatThrownBy(() -> check("simulated", new String[0], "dev"))
                .hasMessageContaining("SimulatedDriveProvider")
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain("Simulation is permitted only under"));
    }

    /** Runs the check with the given mode and active profiles against a factory holding no providers. */
    private static void check(String mode, String... activeProfiles) {
        check(mode, activeProfiles, new String[0]);
    }

    /**
     * Same fixture with default profiles too. Empty arrays leave Spring's own defaults in place.
     */
    private static void check(String mode, String[] activeProfiles, String... defaultProfiles) {
        MockEnvironment environment = new MockEnvironment();
        if (activeProfiles.length > 0) {
            environment.setActiveProfiles(activeProfiles);
        }
        if (defaultProfiles.length > 0) {
            environment.setDefaultProfiles(defaultProfiles);
        }
        environment.setProperty(HardwareMode.PROPERTY, mode);

        DefaultListableBeanFactory beanFactory = new DefaultListableBeanFactory();
        beanFactory.registerSingleton("environment", environment);

        new HardwareModeCheck().postProcessBeanFactory(beanFactory);
    }
}
