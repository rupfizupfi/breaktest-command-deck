package ch.rupfizupfi.deck.device.simulated;

import ch.rupfizupfi.deck.device.api.Measurement;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SimulatedBenchTest {

    private final SimulatedBench bench = new SimulatedBench(new SimulatedBenchProperties(), new SimulatedFaultSwitches());

    @AfterEach
    void stopBench() {
        bench.stop();
    }

    @Test
    void aRunNeverReadsTheSpecimenItReplaced() throws InterruptedException {
        var stream = new SimulatedLoadCellStreamProvider(bench).open();
        stream.startReading();
        bench.start();
        // The session stream stays open between runs and fills with the old specimen's force.
        Thread.sleep(50);

        bench.prepareRun(SpecimenProfile.POLYAMIDE_WEBBING, 5000, "next run");
        List<Measurement> firstBatch = stream.getNextValues();
        Thread.sleep(30);
        firstBatch = concat(firstBatch, stream.getNextValues());

        assertThat(firstBatch).isNotEmpty()
                .allSatisfy(sample -> assertThat(sample.force()).isGreaterThan(4500f));
        assertThat(stream.droppedSampleCount()).isZero();
    }

    private static List<Measurement> concat(List<Measurement> a, List<Measurement> b) {
        return java.util.stream.Stream.concat(a.stream(), b.stream()).toList();
    }
}
