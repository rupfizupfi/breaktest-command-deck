package ch.rupfizupfi.deck.testrunner;

import ch.rupfizupfi.deck.device.relayswitch.ComportNotFoundException;
import ch.rupfizupfi.deck.device.relayswitch.FourWayRelaySwitch;

public class SuckJob {
    Thread thread;

    public void start() {
        this.thread = new Thread(this::suck);
        this.thread.start();
    }

    protected void suck() {
        try {
            FourWayRelaySwitch relaySwitch = new FourWayRelaySwitch();
            relaySwitch.connect();
            relaySwitch.enableRelay1();
            try {
                Thread.sleep(8000);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
            finally {
                relaySwitch.disableRelay1();
                relaySwitch.disconnect();
            }
        } catch (ComportNotFoundException e) {
            e.printStackTrace();
        }
    }
}
