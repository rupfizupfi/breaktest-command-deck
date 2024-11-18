package ch.rupfizupfi.deck.testrunner;

import ch.rupfizupfi.deck.device.relayswitch.ComportNotFoundException;
import ch.rupfizupfi.deck.device.relayswitch.FourWayRelaySwitch;

public class SuckJob {
    private final int duration;

    SuckJob(int duration) {
        this.duration = duration;
    }

    public void start() {
        Thread thread = new Thread(this::suck);
        thread.start();
    }

    protected void suck() {
        try {
            FourWayRelaySwitch relaySwitch = new FourWayRelaySwitch();
            relaySwitch.connect();
            relaySwitch.enableRelay1();
            try {
                Thread.sleep(this.duration * 1000);
            } catch (InterruptedException e) {
                e.printStackTrace();
            } finally {
                relaySwitch.disableRelay1();
                relaySwitch.disconnect();
            }
        } catch (ComportNotFoundException e) {
            e.printStackTrace();
        }
    }
}
