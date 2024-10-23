package ch.rupfizupfi.deck.device.loadcell;

import ch.rupfizupfi.dscusb.Measurement;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.util.ArrayList;
import java.util.List;

public class ForceBroadcaster implements MeasurementObserver {
    private final SimpMessagingTemplate template;
    private final List<Measurement> wsMeasurements = new ArrayList<>();

    public ForceBroadcaster(SimpMessagingTemplate template) {
        this.template = template;
    }

    @Override
    public void update(List<Measurement> measurements) {
        wsMeasurements.addAll(measurements);
        if (System.currentTimeMillis() - wsMeasurements.getFirst().getTimestamp() > 60) {
            template.convertAndSend("/topic/load-cell", wsMeasurements);
            wsMeasurements.clear();
        }
    }
}
