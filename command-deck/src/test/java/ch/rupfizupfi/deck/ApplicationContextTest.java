package ch.rupfizupfi.deck;

import ch.rupfizupfi.deck.api.services.TestRunnerService;
import ch.rupfizupfi.deck.data.TestParameter;
import ch.rupfizupfi.deck.data.TestResult;
import ch.rupfizupfi.deck.data.TestType;
import ch.rupfizupfi.deck.device.HardwareModeInfo;
import ch.rupfizupfi.deck.device.api.DriveProvider;
import ch.rupfizupfi.deck.device.api.LoadCellStreamProvider;
import ch.rupfizupfi.deck.device.simulated.SimulatedDriveProvider;
import ch.rupfizupfi.deck.device.simulated.SimulatedLoadCellStreamProvider;
import ch.rupfizupfi.deck.rig.RigLimits;
import ch.rupfizupfi.deck.testrunner.StartupRecoveryRunner;
import ch.rupfizupfi.deck.testrunner.TestRunnerFactory;
import ch.rupfizupfi.deck.testrunner.program.TestProgramFactory;
import ch.rupfizupfi.deck.testrunner.program.TestProgramRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
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
 * on.
 *
 * <p>Profile {@code dev} because {@code HardwareModeCheck} permits simulation only under its
 * allowlist; a {@code test} profile would be refused. The in-memory datasource keeps the test off
 * the developer's H2 file at {@code ./.data/deck}, and keeps {@code StartupRecoveryRunner}'s sweep
 * away from real {@code TestResult} rows.
 *
 * <p>{@code classes} is explicit because both modules declare {@code ch.rupfizupfi.deck.Application}.
 */
@SpringBootTest(classes = Application.class, properties = {
        "spring.datasource.url=jdbc:h2:mem:deck-context-test;DB_CLOSE_DELAY=-1;MODE=PostgreSQL;DEFAULT_NULL_ORDERING=HIGH",
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
        // application-dev.properties sets simulated; this pins the announced mode.
        assertThat(context.getBean(HardwareModeInfo.class).isSimulated()).isTrue();
    }

    @Test
    void simulatedHardwareProvidersBackTheDeviceContract() {
        // The @ConditionalOnProperty(deck.hardware.mode=simulated) pair, via the device-api types.
        assertThat(context.getBean(DriveProvider.class))
                .isInstanceOf(SimulatedDriveProvider.class);
        assertThat(context.getBean(LoadCellStreamProvider.class))
                .isInstanceOf(SimulatedLoadCellStreamProvider.class);
    }

    @Test
    void testRunnerFactoryIsRegistered() {
        assertThat(context.getBean(TestRunnerFactory.class)).isNotNull();
    }

    /**
     * OQ-64 step 7: the registry's constructor refuses a context in which a TestType has no program
     * factory, so this context starting is the proof every type has one; each resolves to its own.
     */
    @ParameterizedTest
    @EnumSource(TestType.class)
    void everyTestTypeResolvesToAProgram(TestType type) {
        TestParameter parameter = new TestParameter();
        parameter.type = type;
        parameter.speed = 100;
        parameter.upperShutOffThreshold = 3.0;
        parameter.lowerShutOffThreshold = 1.0;
        parameter.upperTurnForce = 2.2;
        parameter.lowerTurnForce = 2.0;
        parameter.cycleCount = 2;
        TestResult result = new TestResult();
        result.testParameter = parameter;

        assertThat(context.getBean(TestProgramRegistry.class).create(result, RigLimits.DEFAULTS)).isNotNull();
        assertThat(context.getBeansOfType(TestProgramFactory.class)).hasSize(TestType.values().length);
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
        // The unit tests construct it with `new`; only the context pins the @Component wiring.
        assertThat(context.getBean(StartupRecoveryRunner.class)).isNotNull();
    }

    @Test
    void contextObjectMapperWritesNestedObjectsCompactly() {
        // GapRecorder#write's one-line sidecar needs the injected mapper to write compactly; the
        // unit tests use their own mapper.
        ObjectMapper mapper = context.getBean(ObjectMapper.class);
        String json = mapper.writeValueAsString(Map.of("outer", Map.of("inner", List.of(1, 2))));
        assertThat(json).doesNotContain("\n").doesNotContain("\r");
    }
}
