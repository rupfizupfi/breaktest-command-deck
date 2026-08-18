package ch.rupfizupfi.deck.device.simulated;

import ch.rupfizupfi.deck.device.api.Drive;
import ch.rupfizupfi.deck.device.api.DriveProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Registered only in simulated mode. The vendor provider carries the mirror condition, so the two
 * can never both be present — which is what stops "simulated" from silently resolving to hardware.
 */
@Component
@ConditionalOnProperty(name = "deck.hardware.mode", havingValue = "simulated")
public class SimulatedDriveProvider implements DriveProvider {

    private final SimulatedBench bench;
    private final SimulatedFaultSwitches faults;

    public SimulatedDriveProvider(SimulatedBench bench, SimulatedFaultSwitches faults) {
        this.bench = bench;
        this.faults = faults;
    }

    /** A distinct handle every time, onto the one shared plant — tier 2 depends on that distinction. */
    @Override
    public Drive open() {
        return new SimulatedDrive(bench, faults);
    }
}
