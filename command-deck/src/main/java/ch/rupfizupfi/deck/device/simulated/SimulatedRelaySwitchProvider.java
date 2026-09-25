package ch.rupfizupfi.deck.device.simulated;

import ch.rupfizupfi.deck.device.relayswitch.RelaySwitch;
import ch.rupfizupfi.deck.device.relayswitch.RelaySwitchProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Registered only in simulated mode; see {@link SimulatedDriveProvider}. */
@Component
@ConditionalOnProperty(name = "deck.hardware.mode", havingValue = "simulated")
public class SimulatedRelaySwitchProvider implements RelaySwitchProvider {

    @Override
    public RelaySwitch open() {
        return new SimulatedRelaySwitch();
    }
}
