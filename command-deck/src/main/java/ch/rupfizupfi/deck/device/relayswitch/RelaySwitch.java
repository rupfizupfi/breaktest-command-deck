package ch.rupfizupfi.deck.device.relayswitch;

/** A relay board. Every command reports whether its byte reached the relay. */
public interface RelaySwitch {

    boolean connect();

    void disconnect();

    boolean enableRelay1();

    boolean disableRelay1();

    boolean isConnected();
}
