package ch.rupfizupfi.deck.device.relayswitch;

/** Source of relay handles. */
public interface RelaySwitchProvider {

    /** A fresh, unconnected switch per call; the caller connects and disconnects it. */
    RelaySwitch open() throws ComportNotFoundException;
}
