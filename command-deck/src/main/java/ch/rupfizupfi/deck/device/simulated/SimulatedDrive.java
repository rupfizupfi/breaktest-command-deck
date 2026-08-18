package ch.rupfizupfi.deck.device.simulated;

import ch.rupfizupfi.deck.device.api.Drive;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A drive handle onto the shared {@link SimulatedBench}.
 * <p>
 * Handles are per-{@code open()} and independently closeable even though they share one plant, so
 * the safe-stop escalation's tier 2 — drop the live handle, enumerate a fresh one, command through
 * it — is a meaningful exercise rather than a no-op.
 */
public class SimulatedDrive implements Drive {

    private final SimulatedBench bench;
    private final SimulatedFaultSwitches faults;
    /** Generation this handle was opened in; see {@link SimulatedFaultSwitches#isStale(long)}. */
    private final long generation;
    private volatile boolean closed = false;

    SimulatedDrive(SimulatedBench bench, SimulatedFaultSwitches faults) {
        this.bench = bench;
        this.faults = faults;
        this.generation = faults.driveGeneration();
    }

    /** A closed handle answers nothing, exactly like a USB handle whose session has been torn down. */
    private SimulatedBench active() {
        if (closed) {
            throw new IllegalStateException("simulated drive handle is closed");
        }
        if (faults.isActive(SimulatedFault.DRIVE_UNRESPONSIVE)) {
            throw new IllegalStateException("simulated drive is not answering (injected fault)");
        }
        if (faults.isStale(generation)) {
            throw new IllegalStateException(
                    "simulated drive handle went stale and no longer answers (injected fault)");
        }
        return bench;
    }

    @Override
    public void setControlParameters(Boolean start, Boolean generalEnable, Boolean directionIsForward,
                                     Boolean localRemote, Boolean useSecondRamp) {
        SimulatedBench target = active();
        if (start != null) {
            target.setStarted(start);
        }
        if (generalEnable != null) {
            target.setGeneralEnabled(generalEnable);
        }
        if (directionIsForward != null) {
            target.setDirectionForward(directionIsForward);
        }
        // localRemote and useSecondRamp change nothing the plant model represents.
    }

    @Override
    public Map<String, Boolean> getControlParameters() {
        SimulatedBench target = active();
        Map<String, Boolean> values = new LinkedHashMap<>();
        values.put("start", target.started());
        values.put("generalEnable", target.generalEnabled());
        values.put("useSecondRamp", false);
        values.put("directionIsForward", target.directionForward());
        return values;
    }

    @Override
    public void setStart(boolean start) {
        active().setStarted(start);
    }

    @Override
    public void setGeneralEnable(boolean generalEnable) {
        active().setGeneralEnabled(generalEnable);
    }

    @Override
    public void setDirection(boolean directionIsForward) {
        active().setDirectionForward(directionIsForward);
    }

    @Override
    public boolean getDirection() {
        return active().directionForward();
    }

    @Override
    public void setUseSecondRamp(boolean useSecondRamp) {
        active();
    }

    @Override
    public void setSecondSpeedRampTime(int accelerationRampTime, int decelerationRampTime) {
        active();
    }

    @Override
    public void setSpeedReferenceValueAsRpm(int rpm) {
        active().setSpeedReferenceRpm(rpm);
    }

    @Override
    public int getMotorSpeedValueAsRpm() {
        // The bench's own measured speed, never the setpoint — see SimulatedBench#measuredRpm.
        return active().measuredRpm();
    }

    @Override
    public Map<String, Integer> getMotorData() {
        SimulatedBench target = active();
        int rpm = target.measuredRpm();
        Map<String, Integer> values = new LinkedHashMap<>();
        values.put("speed", rpm);
        // Loosely proportional to speed so the dashboard shows something that moves with the motor.
        values.put("current", Math.abs(rpm) / 10);
        values.put("voltage", target.generalEnabled() ? 400 : 0);
        values.put("torque", Math.abs(rpm) / 5);
        return values;
    }

    @Override
    public void setActionInCaseOfCommunicationError(int action) {
        active();
    }

    @Override
    public void close() {
        closed = true;
        if (faults.isActive(SimulatedFault.DRIVE_CLOSE_THROWS)) {
            // Thrown AFTER the handle is marked closed, mirroring a wedged USB session: the caller's
            // best-effort path has to drop the reference whether or not the close succeeded.
            throw new IllegalStateException("simulated drive close failed (injected fault)");
        }
    }
}
