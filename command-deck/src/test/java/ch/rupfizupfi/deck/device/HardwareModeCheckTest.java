package ch.rupfizupfi.deck.device;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pins which deployments may run a simulator. This is a safety boundary, not a convenience: the
 * whole point of {@link HardwareModeCheck} is that a bench must never be driven by a simulator and a
 * simulated run must never be mistaken for a real one.
 *
 * <p>The interesting cases all live in the gap between two profiles that share one properties file.
 * {@code docker} is the deck container — a test and simulation deployment — while a native bench run
 * activates {@code bench}, which <em>groups to</em> {@code docker} and so carries it in its active
 * set. Simulation is permitted for the first and refused for the second, and what separates them is
 * only the extra {@code bench} entry. That is subtle enough to deserve tests that would fail if
 * someone "tidied" {@code bench} onto the allowlist.
 *
 * <p>No Spring context: the check is a {@code BeanFactoryPostProcessor}, so a bare bean factory with
 * an {@code Environment} in it is the whole fixture. With no provider beans registered, the branch
 * that fired is read off the message — naming the profiles means the profile gate refused, naming
 * the providers means it passed and the (deliberately empty) provider check refused next.
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
        // script/run-bench.ps1 sets SPRING_PROFILES_ACTIVE=bench, and the profile group in
        // application.properties makes that [bench, docker]. Allowing `docker` to simulate must not
        // extend to the machine, and this is the assertion that holds that line.
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
        // Never a fallback, in either direction: real without providers refuses everywhere, and the
        // message names the jars rather than an interface.
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
        // Nothing active means the check reads the default set, which Spring reports as [default].
        // Judging the empty active set instead would let any allowlist contain it, so the most
        // likely configuration of all would be the one that slips through.
        assertThatThrownBy(() -> check("simulated", new String[0]))
                .hasMessageContaining("Simulation is permitted only under")
                .hasMessageContaining("[default]");
    }

    @Test
    void aDefaultDevProfileMaySimulate() {
        // spring.profiles.default=dev is as much a decision as activating it, so the profile gate
        // passes and only the provider check is left to refuse.
        assertThatThrownBy(() -> check("simulated", new String[0], "dev"))
                .hasMessageContaining("SimulatedDriveProvider")
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain("Simulation is permitted only under"));
    }

    /** Runs the check with the given mode and active profiles against a factory holding no providers. */
    private static void check(String mode, String... activeProfiles) {
        check(mode, activeProfiles, new String[0]);
    }

    /**
     * Same fixture with the default profiles set too. Each array is applied only when non-empty, so
     * the environment keeps Spring's own defaults — no active profiles and {@code [default]} — for
     * the unconfigured case.
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
