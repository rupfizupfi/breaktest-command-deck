package ch.rupfizupfi.deck.device.loadcell;

import ch.rupfizupfi.deck.device.api.Measurement;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.util.ArrayList;
import java.util.List;

public class ForceBroadcaster implements MeasurementObserver {
    /** Batches frames so the browser gets ~16 updates a second rather than one per 20 ms drain. */
    private static final long FLUSH_INTERVAL_MS = 60;

    private final SimpMessagingTemplate template;

    /**
     * Guarded by {@code this}. Two reader threads really can write here at once — a teardown may
     * leave an abandoned reader running alongside its replacement. Unsynchronized this loses
     * updates and can hit getFirst() on a list another thread just cleared, which surfaces only as
     * the force chart quietly ceasing to update: {@code LoadCellDevice#notifyObservers} swallows
     * what an observer throws.
     */
    private final List<Measurement> wsMeasurements = new ArrayList<>();

    public ForceBroadcaster(SimpMessagingTemplate template) {
        this.template = template;
    }

    @Override
    public void update(List<Measurement> measurements) {
        List<Measurement> batch;
        synchronized (this) {
            wsMeasurements.addAll(measurements);
            // Not merely defensive: an empty batch leaves nothing to take a timestamp from, and the
            // observer contract does not promise a non-empty one.
            if (wsMeasurements.isEmpty()) {
                return;
            }

            if (System.currentTimeMillis() - wsMeasurements.getFirst().timestamp() <= FLUSH_INTERVAL_MS) {
                return;
            }

            batch = List.copyOf(wsMeasurements);
            wsMeasurements.clear();
        }

        // Sent outside the lock, and as a copy: this reaches the broker's outbound channel, and a
        // slow or wedged browser session must not hold up the thread that is collecting force.
        template.convertAndSend("/topic/load-cell", batch);
    }
}
