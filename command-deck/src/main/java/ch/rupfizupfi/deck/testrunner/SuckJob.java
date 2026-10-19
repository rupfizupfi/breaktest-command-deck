package ch.rupfizupfi.deck.testrunner;

import ch.rupfizupfi.deck.api.services.SuckService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.function.Consumer;

public class SuckJob {
    private static final Logger log = LoggerFactory.getLogger(SuckJob.class);

    static final int MAX_OFF_ATTEMPTS = 3;
    static final Duration OFF_RETRY_DELAY = Duration.ofMillis(500);

    private final int duration;
    private final SuckService suckService;
    private final Consumer<String> operatorNotice;
    private final Duration offRetryDelay;

    /**
     * @param operatorNotice where a relay left energized is announced, beside the dashboard's Suck
     *                       box, which stays ticked while {@link SuckService#isEnabled()} reads true
     */
    SuckJob(int duration, SuckService suckService, Consumer<String> operatorNotice) {
        this(duration, suckService, operatorNotice, OFF_RETRY_DELAY);
    }

    SuckJob(int duration, SuckService suckService, Consumer<String> operatorNotice, Duration offRetryDelay) {
        this.duration = duration;
        this.suckService = suckService;
        this.operatorNotice = operatorNotice;
        this.offRetryDelay = offRetryDelay;
    }

    static String offRefusedAlarm(int attempts) {
        return "ALARM: vacuum relay 1 did not accept the off command (" + attempts + " attempts)"
                + " - it may still be energized; switch the vacuum off on the dashboard or at the relay board";
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
            switchOff();
        }
    }

    /**
     * Relay state is not read back, so a refused off-write is retried a bounded number of times and,
     * when none is accepted, announced to the operator. A throwing attempt counts as refused, and an
     * interrupt only skips the waits: the off command must go out whatever stopped the hold.
     */
    private void switchOff() {
        for (int attempt = 1; attempt <= MAX_OFF_ATTEMPTS; attempt++) {
            if (tryDisable()) {
                if (attempt > 1) {
                    log.warn("Vacuum relay accepted the off command on attempt {}", attempt);
                }
                return;
            }
            if (attempt < MAX_OFF_ATTEMPTS && !Thread.currentThread().isInterrupted()) {
                try {
                    Thread.sleep(offRetryDelay.toMillis());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }

        log.error("Vacuum relay did not accept the off command after {} attempts; relay 1 may still be energized",
                MAX_OFF_ATTEMPTS);
        try {
            operatorNotice.accept(offRefusedAlarm(MAX_OFF_ATTEMPTS));
        } catch (RuntimeException e) {
            log.error("Could not announce the energized vacuum relay to the operator", e);
        }
    }

    private boolean tryDisable() {
        try {
            return suckService.disable();
        } catch (RuntimeException e) {
            log.error("Vacuum relay off command failed", e);
            return false;
        }
    }
}
