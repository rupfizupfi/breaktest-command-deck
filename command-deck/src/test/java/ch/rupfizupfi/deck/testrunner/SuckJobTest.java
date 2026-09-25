package ch.rupfizupfi.deck.testrunner;

import ch.rupfizupfi.deck.api.services.SuckService;
import ch.rupfizupfi.deck.device.relayswitch.RelaySwitch;
import ch.rupfizupfi.deck.device.relayswitch.RelaySwitchProvider;
import ch.rupfizupfi.deck.device.simulated.SimulatedRelaySwitch;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.mockito.InOrder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SuckJobTest {

    private final RelaySwitch relay = mock(RelaySwitch.class);

    /** Counts the port openings, so a second owner of the relay is visible. */
    private static class CountingProvider implements RelaySwitchProvider {
        private final RelaySwitch relay;
        private int openCount;

        CountingProvider(RelaySwitch relay) {
            this.relay = relay;
        }

        @Override
        public RelaySwitch open() {
            openCount++;
            return relay;
        }
    }

    @Test
    @Timeout(5)
    void aRelayThatDoesNotSwitchOnSkipsTheHold() {
        when(relay.connect()).thenReturn(false);
        SuckJob job = new SuckJob(2, new SuckService(new CountingProvider(relay)));

        long startedAt = System.nanoTime();
        job.suck();
        long elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000;

        assertThat(elapsedMillis).isLessThan(500);
        verify(relay, never()).enableRelay1();
        verify(relay).disconnect();
    }

    @Test
    @Timeout(10)
    void anEnergizedRelayIsReleasedAfterTheHold() {
        when(relay.connect()).thenReturn(true);
        when(relay.enableRelay1()).thenReturn(true);
        SuckJob job = new SuckJob(1, new SuckService(new CountingProvider(relay)));

        job.suck();

        InOrder order = inOrder(relay);
        order.verify(relay).enableRelay1();
        order.verify(relay).disableRelay1();
        order.verify(relay).disconnect();
    }

    @Test
    @Timeout(10)
    void aRefusedOffCommandStillDisconnects() {
        when(relay.connect()).thenReturn(true);
        when(relay.enableRelay1()).thenReturn(true);
        when(relay.disableRelay1()).thenReturn(false);
        SuckJob job = new SuckJob(1, new SuckService(new CountingProvider(relay)));

        assertThatCode(job::suck).doesNotThrowAnyException();

        InOrder order = inOrder(relay);
        order.verify(relay).disableRelay1();
        order.verify(relay).disconnect();
    }

    @Test
    @Timeout(10)
    void aDashboardHoldAndTheJobShareTheOnePortHandle() {
        SimulatedRelaySwitch shared = new SimulatedRelaySwitch();
        CountingProvider provider = new CountingProvider(shared);
        SuckService service = new SuckService(provider);
        assertThat(service.enable()).isTrue();

        new SuckJob(1, service).suck();

        assertThat(shared.isEnergized()).isFalse();
        assertThat(shared.isConnected()).isFalse();
        assertThat(provider.openCount).isEqualTo(1);
    }
}
