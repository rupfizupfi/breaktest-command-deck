package ch.rupfizupfi.deck.device.simulated;

import ch.rupfizupfi.deck.device.relayswitch.RelaySwitch;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Relay with no port behind it. Commands follow {@code FourWayRelaySwitch}: an unconnected switch
 * takes none of them and reports false.
 */
public class SimulatedRelaySwitch implements RelaySwitch {
    private static final Logger log = LoggerFactory.getLogger(SimulatedRelaySwitch.class);

    private volatile boolean connected;
    private volatile boolean energized;

    @Override
    public boolean connect() {
        connected = true;
        return true;
    }

    @Override
    public void disconnect() {
        connected = false;
    }

    @Override
    public boolean enableRelay1() {
        return set(true);
    }

    @Override
    public boolean disableRelay1() {
        return set(false);
    }

    private boolean set(boolean on) {
        if (!connected) {
            log.warn("Simulated relay command {} dropped: not connected.", on ? 1 : 0);
            return false;
        }

        energized = on;
        log.info("Simulated relay 1 {}.", on ? "energized" : "de-energized");
        return true;
    }

    @Override
    public boolean isConnected() {
        return connected;
    }

    public boolean isEnergized() {
        return energized;
    }
}
