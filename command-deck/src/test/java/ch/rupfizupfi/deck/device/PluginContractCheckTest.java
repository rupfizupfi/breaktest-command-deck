package ch.rupfizupfi.deck.device;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import ch.rupfizupfi.deck.device.api.ContractVersion;
import ch.rupfizupfi.deck.device.api.DeviceApi;
import ch.rupfizupfi.deck.device.api.Drive;
import ch.rupfizupfi.deck.device.api.DriveProvider;
import ch.rupfizupfi.deck.device.api.LoadCellStream;
import ch.rupfizupfi.deck.device.api.LoadCellStreamProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.MapPropertySource;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pins the host side of the contract skew check: in real mode a provider must come from a plugin
 * that reported the contract it was built against, and an older report warns per missing feature.
 *
 * <p>{@link DeviceApi}'s record is JVM-wide and append-only, so every scenario reports from a class
 * of its own, and this top-level class must never report: it encloses every fixture below.
 */
class PluginContractCheckTest {

    private final ListAppender<ILoggingEvent> log = new ListAppender<>();
    private final Logger checkLogger = (Logger) LoggerFactory.getLogger(PluginContractCheck.class);

    @BeforeEach
    void captureWarnings() {
        log.start();
        checkLogger.addAppender(log);
    }

    @AfterEach
    void releaseWarnings() {
        checkLogger.detachAppender(log);
    }

    @Test
    void aProviderWhosePluginNeverReportedRefusesStartupInRealMode() {
        assertThatThrownBy(() -> boot("real", NonReportingDriveConfig.class))
                .rootCause()
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("COMMAND DECK CANNOT START")
                .hasMessageContaining("'nonReportingDrive'")
                .hasMessageContaining("DriveProvider")
                .hasMessageContaining("verifyPluginBuiltAgainst");
    }

    @Test
    void anUnsetModeIsRealAndRefusesToo() {
        // deck.hardware.mode defaults to real; a missing property must not read as exempt.
        assertThatThrownBy(() -> boot(null, NonReportingDriveConfig.class))
                .rootCause()
                .hasMessageContaining("COMMAND DECK CANNOT START");
    }

    @Test
    void aProviderDeclaredByAReportingAutoConfigurationPasses() {
        // The shape of both driver jars: the call in the auto-configuration's initializer, the
        // provider an anonymous class from its @Bean method.
        assertThatCode(() -> boot("real", ReportingLoadCellConfig.class)).doesNotThrowAnyException();
    }

    @Test
    void aProxiedConfigurationIsJudgedByItsUserClass() {
        // proxyBeanMethods=true: the @Bean method resolves on the CGLIB subclass.
        assertThatCode(() -> boot("real", ProxiedReportingDriveConfig.class)).doesNotThrowAnyException();
    }

    @Test
    void aProviderThatReportsFromItsOwnClassPasses() {
        assertThatCode(() -> boot("real", SelfReportingDriveProvider.class)).doesNotThrowAnyException();
    }

    @Test
    void simulatedModeIsExempt() {
        // The simulated providers are deck code and never report, by design.
        assertThatCode(() -> boot("simulated", NonReportingDriveConfig.class)).doesNotThrowAnyException();
    }

    @Test
    void aBuildTimeContextIsExempt() {
        System.setProperty("spring.aot.processing", "true");
        try {
            assertThatCode(() -> boot("real", NonReportingDriveConfig.class)).doesNotThrowAnyException();
        } finally {
            System.clearProperty("spring.aot.processing");
        }
    }

    @Test
    void aLoadCellPluginOlderThanDroppedSampleCountWarns() {
        boot("real", LoadCellBuiltAgainst100Config.class);

        assertThat(warnings()).singleElement().satisfies(warning -> assertThat(warning)
                .contains("dropped samples are not reported")
                .contains("1.0.0")
                .contains("LoadCellStream#droppedSampleCount()"));
    }

    @Test
    void aCurrentLoadCellPluginDoesNotWarn() {
        boot("real", ReportingLoadCellConfig.class);

        assertThat(warnings()).isEmpty();
    }

    @Test
    void aDrivePluginIsNotJudgedOnLoadCellFeatures() {
        boot("real", DriveBuiltAgainst100Config.class);

        assertThat(warnings())
                .noneMatch(warning -> warning.contains("dropped samples"))
                .anyMatch(warning -> warning.contains("Drive#readControlSource()"));
    }

    @Test
    void aDrivePluginOlderThanGatingWarns() {
        boot("real", DriveBuiltAgainst140Config.class);

        assertThat(warnings()).singleElement().satisfies(warning -> assertThat(warning)
                .contains("does not gate its own calls")
                .contains("1.4.0")
                .contains("Drive#gating()"));
    }

    private List<String> warnings() {
        return log.list.stream()
                .filter(event -> event.getLevel() == Level.WARN)
                .map(ILoggingEvent::getFormattedMessage)
                .toList();
    }

    private static void boot(String mode, Class<?> fixture) {
        try (var context = new AnnotationConfigApplicationContext()) {
            if (mode != null) {
                context.getEnvironment().getPropertySources()
                        .addFirst(new MapPropertySource("test", Map.of(HardwareMode.PROPERTY, mode)));
            }
            context.register(PluginContractCheck.class, fixture);
            context.refresh();
        }
    }

    private static Drive noDrive() {
        throw new UnsupportedOperationException("the check must never open a provider");
    }

    private static LoadCellStream noStream() {
        throw new UnsupportedOperationException("the check must never open a provider");
    }

    /** A jar built before the skew check existed. */
    @Configuration(proxyBeanMethods = false)
    static class NonReportingDriveConfig {
        @Bean
        DriveProvider nonReportingDrive() {
            return PluginContractCheckTest::noDrive;
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class ReportingLoadCellConfig {
        ReportingLoadCellConfig() {
            DeviceApi.verifyPluginBuiltAgainst(ContractVersion.VALUE);
        }

        @Bean
        LoadCellStreamProvider reportingLoadCell() {
            return new LoadCellStreamProvider() {
                @Override
                public LoadCellStream open() {
                    return noStream();
                }
            };
        }
    }

    @Configuration
    static class ProxiedReportingDriveConfig {
        ProxiedReportingDriveConfig() {
            DeviceApi.verifyPluginBuiltAgainst(ContractVersion.VALUE);
        }

        @Bean
        DriveProvider proxiedReportingDrive() {
            return PluginContractCheckTest::noDrive;
        }
    }

    static class SelfReportingDriveProvider implements DriveProvider {
        SelfReportingDriveProvider() {
            DeviceApi.verifyPluginBuiltAgainst(ContractVersion.VALUE);
        }

        @Override
        public Drive open() {
            return noDrive();
        }
    }

    /** Before LoadCellStream#droppedSampleCount() (1.1.0); an older minor passes the skew check. */
    @Configuration(proxyBeanMethods = false)
    static class LoadCellBuiltAgainst100Config {
        LoadCellBuiltAgainst100Config() {
            DeviceApi.verifyPluginBuiltAgainst("1.0.0");
        }

        @Bean
        LoadCellStreamProvider oldLoadCell() {
            return PluginContractCheckTest::noStream;
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class DriveBuiltAgainst100Config {
        DriveBuiltAgainst100Config() {
            DeviceApi.verifyPluginBuiltAgainst("1.0.0");
        }

        @Bean
        DriveProvider oldDrive() {
            return PluginContractCheckTest::noDrive;
        }
    }

    /** Before Drive#gating() (1.5.0): readControlSource is there, the driver's own gate is not. */
    @Configuration(proxyBeanMethods = false)
    static class DriveBuiltAgainst140Config {
        DriveBuiltAgainst140Config() {
            DeviceApi.verifyPluginBuiltAgainst("1.4.0");
        }

        @Bean
        DriveProvider gatelessDrive() {
            return PluginContractCheckTest::noDrive;
        }
    }
}
