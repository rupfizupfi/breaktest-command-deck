package ch.rupfizupfi.deck.api.services;

import ch.rupfizupfi.deck.device.relayswitch.ComportNotFoundException;
import ch.rupfizupfi.deck.device.relayswitch.RelaySwitch;
import ch.rupfizupfi.deck.device.relayswitch.RelaySwitchProvider;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class SuckServiceTest {

    private final RelaySwitch relay = mock(RelaySwitch.class);

    /** Counts the port openings, so a retry after a failed enable is visible. */
    private static class CountingProvider implements RelaySwitchProvider {
        private final RelaySwitch relay;
        private final ComportNotFoundException discoveryFailure;
        private int openCount;

        CountingProvider(RelaySwitch relay, ComportNotFoundException discoveryFailure) {
            this.relay = relay;
            this.discoveryFailure = discoveryFailure;
        }

        @Override
        public RelaySwitch open() throws ComportNotFoundException {
            openCount++;
            if (discoveryFailure != null) {
                throw discoveryFailure;
            }
            return relay;
        }
    }

    @Test
    void aPortThatDoesNotOpenLeavesTheVacuumOffAndRetryable() {
        when(relay.connect()).thenReturn(false);
        CountingProvider provider = new CountingProvider(relay, null);
        SuckService service = new SuckService(provider);

        assertThat(service.enable()).isFalse();
        assertThat(service.enable()).isFalse();
        assertThat(provider.openCount).isEqualTo(2);
        verify(relay, times(2)).disconnect();
    }

    @Test
    void anEnergizedRelayIsReportedOnceAndKept() {
        when(relay.connect()).thenReturn(true);
        when(relay.enableRelay1()).thenReturn(true);
        CountingProvider provider = new CountingProvider(relay, null);
        SuckService service = new SuckService(provider);

        assertThat(service.enable()).isTrue();
        assertThat(service.enable()).isTrue();
        assertThat(provider.openCount).isEqualTo(1);
    }

    @Test
    void disableReportsTheRelayStateAndReleasesThePort() {
        when(relay.connect()).thenReturn(true);
        when(relay.enableRelay1()).thenReturn(true);
        when(relay.disableRelay1()).thenReturn(true);
        SuckService service = new SuckService(new CountingProvider(relay, null));
        service.enable();

        assertThat(service.disable()).isTrue();
        verify(relay).disableRelay1();
        verify(relay).disconnect();
    }

    @Test
    void aFailedOffCommandIsReportedAsStillOn() {
        when(relay.connect()).thenReturn(true);
        when(relay.enableRelay1()).thenReturn(true);
        when(relay.disableRelay1()).thenReturn(false);
        SuckService service = new SuckService(new CountingProvider(relay, null));
        service.enable();

        assertThat(service.disable()).isFalse();
        verify(relay).disconnect();
    }

    @Test
    void disablingAVacuumThatIsOffTouchesNoPort() {
        CountingProvider provider = new CountingProvider(relay, null);
        SuckService service = new SuckService(provider);

        assertThat(service.disable()).isTrue();
        assertThat(provider.openCount).isZero();
        verifyNoInteractions(relay);
    }

    @Test
    void theReportedStateFollowsASuccessfulEnable() {
        when(relay.connect()).thenReturn(true);
        when(relay.enableRelay1()).thenReturn(true);
        SuckService service = new SuckService(new CountingProvider(relay, null));

        assertThat(service.isEnabled()).isFalse();
        service.enable();
        assertThat(service.isEnabled()).isTrue();
    }

    /** The dashboard reads the state {@code SuckJob} left behind, not its own last click. */
    @Test
    void aDisableFromAnotherCallerIsVisibleToEveryReader() {
        when(relay.connect()).thenReturn(true);
        when(relay.enableRelay1()).thenReturn(true);
        when(relay.disableRelay1()).thenReturn(true);
        SuckService service = new SuckService(new CountingProvider(relay, null));

        service.enable();
        service.disable();

        assertThat(service.isEnabled()).isFalse();
    }

    @Test
    void anUndiscoverablePortLeavesTheVacuumOff() {
        SuckService service = new SuckService(new CountingProvider(relay, new ComportNotFoundException("no such port")));

        assertThat(service.enable()).isFalse();
        verifyNoInteractions(relay);
    }
}
