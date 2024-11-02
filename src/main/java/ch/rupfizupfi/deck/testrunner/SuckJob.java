package ch.rupfizupfi.deck.testrunner;

import ch.rupfizupfi.deck.device.relayswitch.ComportNotFoundException;
import ch.rupfizupfi.deck.device.relayswitch.FourWayRelaySwitch;

public class SuckJob {
    Thread thread;

    public void start() {
        try {
            this.thread = new Thread(this::suck);
            this.thread.start();
        } catch (ComportNotFoundException e) {
            e.printStackTrace();
        }
    }

    protected void suck() throws ComportNotFoundException {
        FourWayRelaySwitch relaySwitch = new FourWayRelaySwitch();
        relaySwitch.connect();
        relaySwitch.enableRelay1();

        try {

            Thread.sleep(5000);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        finally {
            relaySwitch.disableRelay1();
            relaySwitch.disconnect();
        }
    }
}
