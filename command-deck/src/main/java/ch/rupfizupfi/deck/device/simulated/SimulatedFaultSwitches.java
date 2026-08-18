package ch.rupfizupfi.deck.device.simulated;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Which faults the bench is currently injecting.
 * <p>
 * Process-scoped and never persisted: a fault must not survive a restart, or a developer who left
 * one armed yesterday debugs a machine that is lying to them today.
 */
@Component
@ConditionalOnProperty(name = "deck.hardware.mode", havingValue = "simulated")
public class SimulatedFaultSwitches {

    private static final Logger logger = LoggerFactory.getLogger(SimulatedFaultSwitches.class);

    private final Set<SimulatedFault> active = Collections.synchronizedSet(EnumSet.noneOf(SimulatedFault.class));

    /**
     * Bumped whenever {@link SimulatedFault#DRIVE_STALE_HANDLE} is armed. A handle carries the value
     * it was opened with, so "opened before the fault" is expressible — which is what lets tier 2's
     * fresh handle succeed where tier 1's failed.
     */
    private final AtomicLong driveGeneration = new AtomicLong();

    public boolean isActive(SimulatedFault fault) {
        return active.contains(fault);
    }

    public Set<SimulatedFault> active() {
        synchronized (active) {
            return EnumSet.copyOf(active.isEmpty() ? EnumSet.noneOf(SimulatedFault.class) : active);
        }
    }

    public void arm(SimulatedFault fault) {
        if (active.add(fault)) {
            if (fault == SimulatedFault.DRIVE_STALE_HANDLE) {
                driveGeneration.incrementAndGet();
            }
            logger.warn("SIMULATED FAULT ARMED: {} - {}", fault, fault.description());
        }
    }

    public void clear(SimulatedFault fault) {
        if (active.remove(fault)) {
            logger.warn("SIMULATED FAULT CLEARED: {}", fault);
        }
    }

    public void clearAll() {
        synchronized (active) {
            if (!active.isEmpty()) {
                logger.warn("SIMULATED FAULTS CLEARED: {}", active);
                active.clear();
            }
        }
    }

    long driveGeneration() {
        return driveGeneration.get();
    }

    /** True when this handle predates the stale-handle fault and must therefore refuse. */
    boolean isStale(long handleGeneration) {
        return isActive(SimulatedFault.DRIVE_STALE_HANDLE) && handleGeneration < driveGeneration.get();
    }
}
