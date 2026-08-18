package ch.rupfizupfi.deck.device.simulated;

import ch.rupfizupfi.deck.device.api.LoadCellStream;
import ch.rupfizupfi.deck.device.api.LoadCellStreamProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Registered only in simulated mode; see {@link SimulatedDriveProvider}. */
@Component
@ConditionalOnProperty(name = "deck.hardware.mode", havingValue = "simulated")
public class SimulatedLoadCellStreamProvider implements LoadCellStreamProvider {

    private final SimulatedBench bench;

    public SimulatedLoadCellStreamProvider(SimulatedBench bench) {
        this.bench = bench;
    }

    /**
     * Opening a stream means the load cell device left zero references, which only happens between
     * runs — so it is also the moment a fresh specimen goes in. See
     * {@code SimulatedBench#mountNewSpecimen} for why the drive's energize edge is not enough.
     */
    @Override
    public LoadCellStream open() {
        bench.mountNewSpecimen("load cell session opened");
        return new SimulatedLoadCellStream(bench);
    }
}
