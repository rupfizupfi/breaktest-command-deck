package ch.rupfizupfi.deck.device.simulated;

import ch.rupfizupfi.deck.device.api.support.GatedDrive;
import ch.rupfizupfi.deck.device.api.support.LaneDeadlines;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A drive handle onto the shared {@link SimulatedBench}, gated like a 1.5.0 driver: device-api's
 * {@link GatedDrive} over {@link SimulatedDriveOperations}, so the simulated bench runs the same
 * scheduler, lanes and deadlines the CFW11 driver will. Like that driver it feeds the link watchdog
 * it armed ({@link SimulatedLinkKeepAlive}), so a run needs no keep-alive from the deck.
 * <p>
 * Handles are per-{@code open()} and independently closeable, so the safe-stop escalation's tier 2
 * is exercised for real. Each has its own daemon I/O thread, {@code simulated-drive-io-<n>}, and
 * once armed a virtual keep-alive thread named after it.
 */
public class SimulatedDrive extends GatedDrive {

    /**
     * STOP is the contract's 1500 ms. The others stand in until the CFW11 driver derives its own
     * from the vendor timeouts (100 ms per reply, 500 ms for a handle's first write): a read is one
     * transaction, a control or setpoint write one or two, a CFG change a read-back besides.
     */
    static final LaneDeadlines DEADLINES = LaneDeadlines.of(Duration.ofMillis(1000), Duration.ofMillis(1000),
            Duration.ofMillis(2000), Duration.ofMillis(500));

    private static final AtomicInteger HANDLES = new AtomicInteger();

    private final SimulatedLinkKeepAlive keepAlive;

    SimulatedDrive(SimulatedBench bench, SimulatedFaultSwitches faults) {
        this(bench, faults, new SimulatedLinkKeepAlive());
    }

    private SimulatedDrive(SimulatedBench bench, SimulatedFaultSwitches faults, SimulatedLinkKeepAlive keepAlive) {
        super(new SimulatedDriveOperations(bench, faults, keepAlive),
                "simulated-drive-io-" + HANDLES.incrementAndGet(), DEADLINES);
        this.keepAlive = keepAlive;
        keepAlive.bind(ioThreadName() + "-keepalive", () -> async().getMotorSpeedValueAsRpm());
    }

    /** This handle's watchdog feed, for a test. */
    SimulatedLinkKeepAlive keepAlive() {
        return keepAlive;
    }
}
