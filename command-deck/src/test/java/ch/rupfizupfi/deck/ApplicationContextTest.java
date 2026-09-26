package ch.rupfizupfi.deck;

import ch.rupfizupfi.deck.api.services.TestRunnerService;
import ch.rupfizupfi.deck.device.HardwareModeInfo;
import ch.rupfizupfi.deck.device.api.DriveProvider;
import ch.rupfizupfi.deck.device.api.LoadCellStreamProvider;
import ch.rupfizupfi.deck.device.simulated.SimulatedDriveProvider;
import ch.rupfizupfi.deck.device.simulated.SimulatedLoadCellStreamProvider;
import ch.rupfizupfi.deck.testrunner.StartupRecoveryRunner;
import ch.rupfizupfi.deck.testrunner.TestRunnerFactory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.ObjectMapper;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Boots the full command-deck context as {@code bootRun} does and asserts the beans a run depends
 * on. An empty {@code contextLoads()} proves only that nothing threw,
 * so every method names what it pins.
 *
 * <p>Profile {@code dev} is not a convenience here, it is the only legal choice:
 * {@code HardwareModeCheck} permits {@code deck.hardware.mode=simulated} only when every active
 * profile is in its {@code SIMULATION_PROFILES} allowlist ({@code dev}), and a {@code test} profile
 * would be refused at boot — deliberately. The datasource override below is what makes {@code dev}
 * safe: without it the test would open the developer's file-backed H2 at {@code ./.data/deck}
 * (racing a running app for the lock), and {@code StartupRecoveryRunner} — which fires on
 * {@code ApplicationReadyEvent}, published by {@code @SpringBootTest}'s SpringApplication boot —
 * would sweep real non-terminal {@code TestResult} rows. Against the throwaway in-memory H2 the
 * sweep sees only {@code data.sql} seed data.
 *
 * <p>{@code classes} is explicit because both modules declare a main class with the same FQCN,
 * {@code ch.rupfizupfi.deck.Application} — this module's own class output precedes the cms jar on
 * the test compile classpath, so the reference below resolves to command-deck's Application, and
 * stating it keeps the choice out of configuration detection's hands.
 *
 * <p>No Vaadin suppression is needed: the default MOCK web environment never starts a servlet
 * container, so the servlet-driven dev-mode frontend tooling never runs, and {@code vaadin-dev} is
 * {@code developmentOnly} — off the test classpath entirely. {@code vaadin.launch-browser=false}
 * is defence in depth.
 */
@SpringBootTest(classes = Application.class, properties = {
        "spring.datasource.url=jdbc:h2:mem:deck-context-test;DB_CLOSE_DELAY=-1",
        "vaadin.launch-browser=false"
})
@ActiveProfiles("dev")
class ApplicationContextTest {

    @Autowired
    ApplicationContext context;

    @Autowired
    DataSource dataSource;

    @Test
    void datasourceIsInMemoryH2NotTheDevFile() throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            String url = connection.getMetaData().getURL();
            assertThat(url).contains(":mem:");
            assertThat(url).doesNotContain(".data");
        }
    }

    @Test
    void hardwareModeResolvesToSimulated() {
        // dev's application-dev.properties promises simulated hardware; HardwareModeCheck already
        // refused the boot if that promise could not be served, so this pins the announced mode.
        assertThat(context.getBean(HardwareModeInfo.class).isSimulated()).isTrue();
    }

    @Test
    void simulatedHardwareProvidersBackTheDeviceContract() {
        // The @ConditionalOnProperty(deck.hardware.mode=simulated) pair dev mode promises. Asserted
        // through the device-api contract types: these are the beans HardwareModeCheck requires and
        // DeviceService consumes.
        assertThat(context.getBean(DriveProvider.class))
                .isInstanceOf(SimulatedDriveProvider.class);
        assertThat(context.getBean(LoadCellStreamProvider.class))
                .isInstanceOf(SimulatedLoadCellStreamProvider.class);
    }

    @Test
    void testRunnerFactoryIsRegistered() {
        assertThat(context.getBean(TestRunnerFactory.class)).isNotNull();
    }

    @Test
    void testRunnerServiceBrowserEndpointIsRegistered() {
        // The @BrowserCallable behind the run controls; constructing it also proves
        // TestRunnerFactory can build its TestRunnerThread.
        assertThat(context.getBean(TestRunnerService.class)).isNotNull();
    }

    @Test
    void stompMessagingTemplateIsRegistered() {
        // The WebSocket broker path every live broadcast (force, test state) rides on.
        assertThat(context.getBean(SimpMessagingTemplate.class)).isNotNull();
    }

    @Test
    void startupRecoveryRunnerIsABeanNotJustAClass() {
        // The unit tests construct it with `new`; only the context can pin the @Component
        // wiring that gives every boot its orphan sweep and de-energize.
        assertThat(context.getBean(StartupRecoveryRunner.class)).isNotNull();
    }

    @Test
    void contextObjectMapperWritesNestedObjectsCompactly() {
        // GapRecorder's one-line sidecar invariant (see GapRecorder#write) holds only if the
        // context-injected mapper writes compactly; the unit tests use their own mapper, so
        // the injected one is pinned here.
        ObjectMapper mapper = context.getBean(ObjectMapper.class);
        String json = mapper.writeValueAsString(Map.of("outer", Map.of("inner", List.of(1, 2))));
        assertThat(json).doesNotContain("\n").doesNotContain("\r");
    }
}
