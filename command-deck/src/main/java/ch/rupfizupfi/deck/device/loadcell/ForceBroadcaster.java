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
     * Guarded by {@code this}: a teardown may leave an abandoned reader running alongside its
     * replacement, so two threads can write here at once.
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
            // The observer contract does not promise a non-empty batch.
            if (wsMeasurements.isEmpty()) {
                return;
            }

            if (System.currentTimeMillis() - wsMeasurements.getFirst().timestamp() <= FLUSH_INTERVAL_MS) {
                return;
            }

            batch = List.copyOf(wsMeasurements);
            wsMeasurements.clear();
        }

        // Outside the lock, so a slow browser session cannot hold up the reader thread.
        template.convertAndSend("/topic/load-cell", batch);
    }

    /**
     * Sends whatever is buffered regardless of its age, so the tail below {@code FLUSH_INTERVAL_MS}
     * reaches the chart of the run that produced it rather than the next run's.
     */
    @Override
    public void flush() {
        List<Measurement> batch;
        synchronized (this) {
            if (wsMeasurements.isEmpty()) {
                return;
            }

            batch = List.copyOf(wsMeasurements);
            wsMeasurements.clear();
        }

        template.convertAndSend("/topic/load-cell", batch);
    }
}
