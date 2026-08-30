package ch.rupfizupfi.deck.device.simulated;

import ch.rupfizupfi.deck.device.api.LoadCellStream;
import ch.rupfizupfi.deck.device.api.LoadCellStreamProvider;
import ch.rupfizupfi.deck.device.loadcell.SessionAwareStreamProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Registered only in simulated mode; see {@link SimulatedDriveProvider}. */
@Component
@ConditionalOnProperty(name = "deck.hardware.mode", havingValue = "simulated")
public class SimulatedLoadCellStreamProvider implements LoadCellStreamProvider, SessionAwareStreamProvider {

    private final SimulatedBench bench;

    public SimulatedLoadCellStreamProvider(SimulatedBench bench) {
        this.bench = bench;
    }

    @Override
    public LoadCellStream open() {
        return open(false);
    }

    /**
     * A first open means the load cell device left zero references, which only happens between runs —
     * so it is also the moment a fresh specimen goes in. See {@code SimulatedBench#mountNewSpecimen}
     * for why the drive's energize edge is not enough.
     * <p>
     * A reconnect must not remount: it would zero the crosshead and heal the fracture underneath a
     * live test, post-reconnect force would read ~0, and the resume drift gate — which compares
     * against the last force seen before the loss — would then reject every simulated resume. That
     * makes the feature unverifiable and the only pressure it creates is pressure to weaken the gate,
     * which is fatal: a drop to zero is exactly what a garbage reconnect looks like.
     */
    @Override
    public LoadCellStream open(boolean reconnect) {
        if (bench.faults().isActive(SimulatedFault.LOAD_CELL_STREAM_OPEN_FAILS)) {
            // Thrown here rather than injected in the bench - why, in
            // doc/06-feature-work/virtual-devices/fault-injection.md.
            throw new IllegalStateException("simulated fault: the load cell stream cannot be opened");
        }

        if (!reconnect) {
            bench.mountNewSpecimen("load cell session opened");
        }
        return new SimulatedLoadCellStream(bench);
    }
}
