package ch.rupfizupfi.deck.api.services;

import ch.rupfizupfi.deck.device.simulated.SimulatedFault;
import ch.rupfizupfi.deck.device.simulated.SimulatedFaultSwitches;
import com.vaadin.hilla.BrowserCallable;
import jakarta.annotation.security.RolesAllowed;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

import java.util.Arrays;
import java.util.List;

/**
 * Arms and clears the bench's fault switches, so the detectors and the safe-stop escalation can be
 * exercised on demand.
 * <p>
 * Exists only in simulated mode, on the same condition as the simulated providers, so a real bench
 * has no such endpoint. Admin-only as well.
 */
@BrowserCallable
@RolesAllowed("ADMIN")
@ConditionalOnProperty(name = "deck.hardware.mode", havingValue = "simulated")
public class SimulatedFaultService {

    private final SimulatedFaultSwitches switches;

    public SimulatedFaultService(SimulatedFaultSwitches switches) {
        this.switches = switches;
    }

    public List<FaultState> list() {
        return Arrays.stream(SimulatedFault.values())
                .map(fault -> new FaultState(fault.name(), fault.description(), switches.isActive(fault)))
                .toList();
    }

    public List<FaultState> arm(String fault) {
        switches.arm(parse(fault));
        return list();
    }

    public List<FaultState> clear(String fault) {
        switches.clear(parse(fault));
        return list();
    }

    public List<FaultState> clearAll() {
        switches.clearAll();
        return list();
    }

    private static SimulatedFault parse(String fault) {
        try {
            return SimulatedFault.valueOf(fault);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("unknown simulated fault: " + fault
                    + ". Known: " + Arrays.toString(SimulatedFault.values()));
        }
    }

    public record FaultState(String fault, String description, boolean armed) {
    }
}
