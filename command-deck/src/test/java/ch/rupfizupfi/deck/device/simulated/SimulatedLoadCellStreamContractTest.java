package ch.rupfizupfi.deck.device.simulated;

import ch.rupfizupfi.deck.device.api.LoadCellStream;
import ch.rupfizupfi.deck.device.api.Measurement;
import ch.rupfizupfi.deck.device.api.StreamFailure;
import ch.rupfizupfi.deck.device.api.contract.LoadCellStreamContractTest;

/**
 * Holds {@link SimulatedLoadCellStream} to the {@link LoadCellStream} contract the load-cell driver
 * is held to. The bench's tick never runs; the fixture plays its part, offering, dropping and
 * failing the stream the way the tick does.
 */
class SimulatedLoadCellStreamContractTest extends LoadCellStreamContractTest {

    @Override
    protected Fixture createFixture() {
        var bench = new SimulatedBench(new SimulatedBenchProperties(), new SimulatedFaultSwitches());
        var provider = new SimulatedLoadCellStreamProvider(bench);
        return new Fixture() {
            private SimulatedLoadCellStream stream;

            @Override
            public LoadCellStream open() {
                stream = (SimulatedLoadCellStream) provider.open();
                return stream;
            }

            @Override
            public void feed(float... forces) {
                for (float force : forces) {
                    stream.offer(new Measurement(force, System.currentTimeMillis()));
                }
            }

            @Override
            public void dropReadings(int count) {
                for (int i = 0; i < count; i++) {
                    stream.drop();
                }
            }

            @Override
            public void injectFault() {
                // As SimulatedFault.LOAD_CELL_STREAM_DEATH makes the tick do.
                stream.fail(new StreamFailure("-800", "CommandExecutionException", "injected fault"));
            }
        };
    }
}
