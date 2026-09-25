package ch.rupfizupfi.deck.device.relayswitch;

import ch.rupfizupfi.deck.device.HardwareMode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Registered in real mode, an absent {@code deck.hardware.mode} included, mirroring the vendor
 * auto-configurations; {@link ch.rupfizupfi.deck.device.simulated.SimulatedRelaySwitchProvider}
 * takes the disjoint {@code simulated} value. Port discovery happens per {@link #open()}, so the
 * bean exists with no relay attached.
 */
@Component
@ConditionalOnProperty(name = HardwareMode.PROPERTY, havingValue = "real", matchIfMissing = true)
public class SerialRelaySwitchProvider implements RelaySwitchProvider {

    private final String portDescription;

    public SerialRelaySwitchProvider(@Value("${device.relay.port-description}") String portDescription) {
        this.portDescription = portDescription;
    }

    @Override
    public RelaySwitch open() throws ComportNotFoundException {
        return new FourWayRelaySwitch(portDescription);
    }
}
