package ch.rupfizupfi.deck.device.simulated;

import ch.rupfizupfi.deck.device.api.ControlSource;
import ch.rupfizupfi.deck.device.api.DriveCommunicationException;
import ch.rupfizupfi.deck.device.api.support.DriveOperations;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One handle's transactions with the {@link SimulatedBench}'s drive, as {@link SimulatedDrive}'s
 * gate dispatches them: on its I/O thread, one at a time. Each takes the bench's
 * {@linkplain SimulatedBench#transactionLatency() transaction latency} before it reaches the plant.
 */
final class SimulatedDriveOperations implements DriveOperations {

    /** The real drive's P0314 tops out at 999.0 s. */
    private static final Duration MAX_LINK_LOSS_TIMEOUT = Duration.ofMillis(999_000);
    /** And it counts in tenths of a second. */
    private static final long LINK_LOSS_RESOLUTION_MILLIS = 100;
    /** The real drive's ramp times (P0100-P0103) top out at 999.0 s, in tenths. */
    private static final int MAX_RAMP_TIME_TENTHS = 9990;

    private final SimulatedBench bench;
    private final SimulatedFaultSwitches faults;
    /** This handle's feed for the link watchdog, told of every transaction. */
    private final SimulatedLinkKeepAlive keepAlive;
    /** Generation this handle was opened in; see {@link SimulatedFaultSwitches#isStale(long)}. */
    private final long generation;
    private volatile boolean closed = false;

    SimulatedDriveOperations(SimulatedBench bench, SimulatedFaultSwitches faults, SimulatedLinkKeepAlive keepAlive) {
        this.bench = bench;
        this.faults = faults;
        this.keepAlive = keepAlive;
        this.generation = faults.driveGeneration();
    }

    /**
     * One request-reply exchange. A closed handle answers nothing, like a torn-down USB session.
     * Only a request that gets past the faults and the bus time reaches the drive, so only it feeds
     * the link watchdog: a wedged transaction starves it, as a hung transfer would.
     * <p>
     * The injected link faults throw {@link DriveCommunicationException}, the type a real driver
     * names a lost link with; a call on a closed handle is the caller's mistake, not a link fault.
     */
    private SimulatedBench transact() {
        if (closed) {
            throw new IllegalStateException("simulated drive handle is closed");
        }
        keepAlive.transactionStarted();
        try {
            if (faults.isActive(SimulatedFault.DRIVE_UNRESPONSIVE)) {
                throw new DriveCommunicationException("simulated drive is not answering (injected fault)");
            }
            if (faults.isStale(generation)) {
                throw new DriveCommunicationException(
                        "simulated drive handle went stale and no longer answers (injected fault)");
            }
            if (faults.consume(SimulatedFault.DRIVE_TRANSACTION_WEDGED)) {
                bench.holdWedge();
            }
            pause(bench.transactionLatency());
            bench.requestReceived();
            return bench;
        } finally {
            keepAlive.transactionEnded();
        }
    }

    /** Uninterruptible, like the native transfer it stands for. */
    private static void pause(Duration duration) {
        long end = System.nanoTime() + duration.toNanos();
        boolean interrupted = false;
        for (long left; (left = end - System.nanoTime()) > 0; ) {
            try {
                Thread.sleep(Duration.ofNanos(left));
            } catch (InterruptedException e) {
                interrupted = true;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    /** One telegram, as the CFW11 driver writes P0682 and P0683 together. */
    @Override
    public void stop() {
        keepAlive.deEnergizing();
        SimulatedBench target = transact();
        target.setStarted(false);
        target.setGeneralEnabled(false);
        target.setSpeedReferenceRpm(0);
    }

    @Override
    public void setControlParameters(Boolean start, Boolean generalEnable, Boolean directionIsForward,
                                     Boolean localRemote, Boolean useSecondRamp) {
        if (Boolean.FALSE.equals(start) || Boolean.FALSE.equals(generalEnable)) {
            keepAlive.deEnergizing();
        }
        SimulatedBench target = transact();
        if (start != null) {
            target.setStarted(start);
        }
        if (generalEnable != null) {
            target.setGeneralEnabled(generalEnable);
        }
        if (directionIsForward != null) {
            target.setDirectionForward(directionIsForward);
        }
        if (useSecondRamp != null) {
            target.setUseSecondRamp(useSecondRamp);
        }
        // localRemote changes nothing the plant model represents.
    }

    @Override
    public Map<String, Boolean> getControlParameters() {
        SimulatedBench target = transact();
        Map<String, Boolean> values = new LinkedHashMap<>();
        values.put("start", target.started());
        values.put("generalEnable", target.generalEnabled());
        values.put("useSecondRamp", target.useSecondRamp());
        values.put("directionIsForward", target.directionForward());
        return values;
    }

    /**
     * The bench's state, never the {@code localRemote} request, which this plant ignores. LOCAL
     * unless a test sets otherwise through {@link SimulatedBench#setControlSource}.
     */
    @Override
    public ControlSource readControlSource() {
        return transact().controlSource();
    }

    @Override
    public void setStart(boolean start) {
        if (!start) {
            keepAlive.deEnergizing();
        }
        transact().setStarted(start);
    }

    @Override
    public void setGeneralEnable(boolean generalEnable) {
        if (!generalEnable) {
            keepAlive.deEnergizing();
        }
        transact().setGeneralEnabled(generalEnable);
    }

    @Override
    public void setDirection(boolean directionIsForward) {
        transact().setDirectionForward(directionIsForward);
    }

    @Override
    public boolean getDirection() {
        return transact().directionForward();
    }

    @Override
    public void setUseSecondRamp(boolean useSecondRamp) {
        transact().setUseSecondRamp(useSecondRamp);
    }

    /** Refused like the CFW11 driver refuses it, before anything reaches the drive. */
    @Override
    public void setSecondSpeedRampTime(int accelerationRampTime, int decelerationRampTime) {
        if (accelerationRampTime < 0 || accelerationRampTime > MAX_RAMP_TIME_TENTHS
                || decelerationRampTime < 0 || decelerationRampTime > MAX_RAMP_TIME_TENTHS) {
            throw new IllegalArgumentException("ramp times take 0.." + MAX_RAMP_TIME_TENTHS
                    + " tenths of a second, got " + accelerationRampTime + " and " + decelerationRampTime);
        }
        transact();
    }

    @Override
    public void setSpeedReferenceValueAsRpm(int rpm) {
        transact().setSpeedReferenceRpm(rpm);
    }

    @Override
    public int getMotorSpeedValueAsRpm() {
        // The bench's own measured speed, never the setpoint - see SimulatedBench#measuredRpm.
        return transact().measuredRpm();
    }

    @Override
    public Map<String, Integer> getMotorData() {
        SimulatedBench target = transact();
        int rpm = target.measuredRpm();
        Map<String, Integer> values = new LinkedHashMap<>();
        values.put("speed", rpm);
        // Loosely proportional to speed so the dashboard shows something that moves with the motor.
        // In the contract's units, as the CFW11 reports them: current in tenths of an ampere
        // (10.0 A at 1000 rpm), torque in tenths of a percent of rated torque (20.0 % at 1000 rpm).
        values.put("current", Math.abs(rpm) / 10);
        values.put("voltage", target.generalEnabled() ? 400 : 0);
        values.put("torque", Math.abs(rpm) / 5);
        return values;
    }

    /** Like the CFW11's P0313: a CFG parameter, so a change is refused while the shaft turns. */
    @Override
    public void setActionInCaseOfCommunicationError(int action) {
        SimulatedBench target = transact();
        if (target.communicationErrorAction() != action) {
            refuseWhileTurning(target, "communication-error action");
            target.setCommunicationErrorAction(action);
        }
    }

    private static void refuseWhileTurning(SimulatedBench target, String setting) {
        if (target.measuredRpm() != 0) {
            throw new IllegalStateException("simulated drive refuses to change the " + setting
                    + " while the motor turns at " + target.measuredRpm() + " rpm (CFG parameter)");
        }
    }

    /** Validates and rounds as the CFW11 driver does, so a deck-side mistake fails here first. */
    @Override
    public void enableLinkLossStop(Duration timeout) {
        if (timeout == null || timeout.isNegative() || timeout.isZero()
                || timeout.compareTo(MAX_LINK_LOSS_TIMEOUT) > 0) {
            throw new IllegalArgumentException("link-loss timeout must be above 0 and at most "
                    + MAX_LINK_LOSS_TIMEOUT + ", got " + timeout);
        }
        SimulatedBench target = transact();
        int action = target.communicationErrorAction();
        if (action != 1 && action != 2 && action != 5) {
            throw new IllegalStateException("communication-error action " + action
                    + " would not stop the motor, refusing to arm the link-loss stop");
        }
        long step = Duration.ofMillis(LINK_LOSS_RESOLUTION_MILLIS).toNanos();
        long rounded = Math.ceilDiv(timeout.toNanos(), step) * step;
        if (target.linkLossTimeoutNanos() != rounded) {
            // P0314 is CFG as well.
            refuseWhileTurning(target, "link-loss timeout");
            target.setLinkLossTimeoutNanos(rounded);
        }
        keepAlive.armed(Duration.ofNanos(rounded));
    }

    /** On the handle's I/O thread, after its last transaction; a failure reaches the closer. */
    @Override
    public void close() {
        closed = true;
        keepAlive.close();
        if (faults.isActive(SimulatedFault.DRIVE_CLOSE_THROWS)) {
            // After marking closed, mirroring a wedged USB session.
            throw new IllegalStateException("simulated drive close failed (injected fault)");
        }
    }
}
