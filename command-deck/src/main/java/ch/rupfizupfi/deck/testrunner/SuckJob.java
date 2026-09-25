package ch.rupfizupfi.deck.testrunner;

import ch.rupfizupfi.deck.api.services.SuckService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SuckJob {
    private static final Logger log = LoggerFactory.getLogger(SuckJob.class);

    private final int duration;
    private final SuckService suckService;

    SuckJob(int duration, SuckService suckService) {
        this.duration = duration;
        this.suckService = suckService;
    }

    public void start() {
        Thread thread = new Thread(this::suck, "vacuum-relay");
        thread.start();
    }

    /** Holds the relay on for the configured duration, and only once the relay is confirmed on. */
    protected void suck() {
        if (!suckService.enable()) {
            log.error("Suction skipped: the relay did not switch on.");
            return;
        }

        try {
            Thread.sleep(this.duration * 1000L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            // Relay state is not read back, so this log line is the whole record of a refused off-write.
            if (!suckService.disable()) {
                log.error("Vacuum relay did not accept the off command; relay 1 may still be energized");
            }
        }
    }
}
