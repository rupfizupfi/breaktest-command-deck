package ch.rupfizupfi.deck.api.services;

import ch.rupfizupfi.deck.device.relayswitch.ComportNotFoundException;
import ch.rupfizupfi.deck.device.relayswitch.FourWayRelaySwitch;
import com.vaadin.hilla.BrowserCallable;
import jakarta.annotation.security.PermitAll;

@BrowserCallable
@PermitAll
public class SuckService {
    protected FourWayRelaySwitch suckSwitch;
    protected boolean isEnabled = false;

    public boolean enable() {
        if (isEnabled) {
            return false;
        }

        try {
            suckSwitch = new FourWayRelaySwitch();
            suckSwitch.connect();
            suckSwitch.enableRelay1();
        } catch (ComportNotFoundException e) {
            return false;
        }

        isEnabled = true;
        return true;
    }

    public boolean disable() {
        if (!isEnabled) {
            return false;
        }

        isEnabled = false;
        suckSwitch.disableRelay1();
        suckSwitch.disconnect();
        suckSwitch = null;
        return true;
    }
}
