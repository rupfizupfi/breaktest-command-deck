package ch.rupfizupfi.deck.device.loadcell;

import ch.rupfizupfi.dscusb.Measurement;

import java.util.List;

public interface MeasurementObserver {
    void update(List<Measurement> measurements);
}
