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
     * A first open happens only between runs, so it mounts a fresh specimen; see
     * {@code SimulatedBench#mountNewSpecimen}.
     * <p>
     * A reconnect must not remount: post-reconnect force would read ~0 and the drift gate would
     * reject every simulated resume.
     */
    @Override
    public LoadCellStream open(boolean reconnect) {
        if (bench.faults().isActive(SimulatedFault.LOAD_CELL_STREAM_OPEN_FAILS)) {
            // Thrown here rather than in the bench; see
            // doc/dev/backend/fault-injection.md.
            throw new IllegalStateException("simulated fault: the load cell stream cannot be opened");
        }

        if (!reconnect) {
            bench.mountNewSpecimen("load cell session opened");
        }
        return new SimulatedLoadCellStream(bench);
    }
}
