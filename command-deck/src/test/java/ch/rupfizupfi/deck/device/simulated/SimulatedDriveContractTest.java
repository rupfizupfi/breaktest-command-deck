package ch.rupfizupfi.deck.device.simulated;

import ch.rupfizupfi.deck.device.api.Drive;
import ch.rupfizupfi.deck.device.api.DriveGating;
import ch.rupfizupfi.deck.device.api.contract.DriveContractTest;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Holds {@link SimulatedDrive} to the {@link Drive} contract the CFW11 driver is held to, the gated
 * concurrency cases included: the simulator answers {@code DRIVER_SERIALIZES}. The bench's tick never
 * runs, so the shaft stays where {@link Fixture#measuredRpm} puts it.
 */
class SimulatedDriveContractTest extends DriveContractTest {

    /** The gated cases skip on an ungated drive; this keeps them from passing by skipping. */
    @Test
    void theSimulatorGatesItsOwnCalls() {
        assertThat(drive.gating()).isEqualTo(DriveGating.DRIVER_SERIALIZES);
    }

    @Override
    protected Fixture createFixture() {
        var faults = new SimulatedFaultSwitches();
        var bench = new SimulatedBench(new SimulatedBenchProperties(), faults);
        var provider = new SimulatedDriveProvider(bench, faults);
        return new Fixture() {
            @Override
            public Drive open() {
                return provider.open();
            }

            @Override
            public void measuredRpm(int rpm) {
                bench.setMeasuredRpm(rpm);
            }

            @Override
            public void injectCommsFault() {
                faults.arm(SimulatedFault.DRIVE_UNRESPONSIVE);
            }

            @Override
            public Duration maxLinkLossTimeout() {
                return Duration.ofMillis(999_000);
            }

            @Override
            public int commandedRpm() {
                return bench.speedReferenceRpm();
            }

            @Override
            public void transactionLatency(Duration latency) {
                bench.setTransactionLatency(latency);
            }

            @Override
            public long lastRequestNanos() {
                return bench.lastRequestNanos();
            }
        };
    }
}
