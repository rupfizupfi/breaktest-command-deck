package ch.rupfizupfi.deck.api.services;

import ch.rupfizupfi.deck.device.relayswitch.ComportNotFoundException;
import ch.rupfizupfi.deck.device.relayswitch.RelaySwitch;
import ch.rupfizupfi.deck.device.relayswitch.RelaySwitchProvider;
import com.vaadin.hilla.BrowserCallable;
import jakarta.annotation.security.PermitAll;

/**
 * The one owner of the vacuum relay: the dashboard toggle and the post-break suction
 * ({@code SuckJob}) both go through it, so at most one port handle exists and {@code isEnabled}
 * is the relay's state. Whichever caller asked last wins.
 */
@BrowserCallable
@PermitAll
public class SuckService {
    private final RelaySwitchProvider relays;
    private RelaySwitch suckSwitch;
    private boolean isEnabled = false;

    public SuckService(RelaySwitchProvider relays) {
        this.relays = relays;
    }

    /** The relay's state as this service last set it; nothing reads it back from the hardware. */
    public synchronized boolean isEnabled() {
        return isEnabled;
    }

    /**
     * Whether the relay is energized when this returns: a port that fails to open or to take the
     * byte leaves nothing connected and reports false.
     */
    public synchronized boolean enable() {
        if (isEnabled) {
            return true;
        }

        RelaySwitch relay;
        try {
            relay = relays.open();
        } catch (ComportNotFoundException e) {
            return false;
        }

        if (!relay.connect() || !relay.enableRelay1()) {
            relay.disconnect();
            return false;
        }

        suckSwitch = relay;
        isEnabled = true;
        return true;
    }

    /**
     * Whether the relay is de-energized when this returns. The port closes either way, so the next
     * {@link #enable()} opens a fresh one rather than reusing a handle whose write just failed.
     */
    public synchronized boolean disable() {
        if (!isEnabled) {
            return true;
        }

        RelaySwitch relay = suckSwitch;
        suckSwitch = null;
        isEnabled = false;

        boolean off = relay.disableRelay1();
        relay.disconnect();
        return off;
    }
}
