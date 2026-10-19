package ch.rupfizupfi.deck.device;

import ch.rupfizupfi.deck.device.api.ContractVersion;
import ch.rupfizupfi.deck.device.api.DeviceApi;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link DeviceApi#verifyPluginBuiltAgainst} records the class that called it, which is what
 * {@link PluginContractCheck} matches providers against. Here rather than in {@code device-api}: the
 * included build's own tests are not part of the JVM gate.
 */
class DeviceApiReportTest {

    @Test
    void aCompatibleCallIsRecordedUnderTheCallerClass() {
        CurrentPlugin.report();

        assertThat(DeviceApi.reportedBuiltAgainst()).containsEntry(CurrentPlugin.class, ContractVersion.VALUE);
    }

    @Test
    void anOlderMinorIsRecordedWithItsOwnVersion() {
        OlderMinorPlugin.report();

        assertThat(DeviceApi.reportedBuiltAgainst()).containsEntry(OlderMinorPlugin.class, "1.0.0");
    }

    @Test
    void anIncompatibleCallIsNotRecorded() {
        assertThatThrownBy(IncompatiblePlugin::report).isInstanceOf(IllegalStateException.class);

        assertThat(DeviceApi.reportedBuiltAgainst()).doesNotContainKey(IncompatiblePlugin.class);
    }

    @Test
    void theViewIsUnmodifiable() {
        assertThatThrownBy(() -> DeviceApi.reportedBuiltAgainst().put(DeviceApiReportTest.class, "1.0.0"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    static class CurrentPlugin {
        static void report() {
            DeviceApi.verifyPluginBuiltAgainst(ContractVersion.VALUE);
        }
    }

    static class OlderMinorPlugin {
        static void report() {
            DeviceApi.verifyPluginBuiltAgainst("1.0.0");
        }
    }

    static class IncompatiblePlugin {
        static void report() {
            DeviceApi.verifyPluginBuiltAgainst("99.0.0");
        }
    }
}
