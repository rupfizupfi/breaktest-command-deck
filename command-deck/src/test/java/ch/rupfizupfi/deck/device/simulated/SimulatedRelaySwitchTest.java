package ch.rupfizupfi.deck.device.simulated;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SimulatedRelaySwitchTest {

    private final SimulatedRelaySwitch relay = new SimulatedRelaySwitch();

    @Test
    void commandsBeforeConnectAreDropped() {
        assertThat(relay.enableRelay1()).isFalse();
        assertThat(relay.isEnergized()).isFalse();
        assertThat(relay.disableRelay1()).isFalse();
        assertThat(relay.isEnergized()).isFalse();
    }

    @Test
    void aConnectedRelayTakesBothCommands() {
        assertThat(relay.connect()).isTrue();

        assertThat(relay.enableRelay1()).isTrue();
        assertThat(relay.isEnergized()).isTrue();

        assertThat(relay.disableRelay1()).isTrue();
        assertThat(relay.isEnergized()).isFalse();
    }

    @Test
    void disconnectingReleasesTheRelay() {
        relay.connect();
        relay.enableRelay1();

        relay.disconnect();

        assertThat(relay.isConnected()).isFalse();
        assertThat(relay.enableRelay1()).isFalse();
    }
}
