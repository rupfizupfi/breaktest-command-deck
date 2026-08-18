package ch.rupfizupfi.deck.device.loadcell;

import ch.rupfizupfi.deck.device.api.Measurement;

import java.util.List;

public interface MeasurementObserver {
    void update(List<Measurement> measurements);
}
