package ch.rupfizupfi.deck.testrunner.program;

import ch.rupfizupfi.deck.data.TestParameter;
import ch.rupfizupfi.deck.testrunner.run.Direction;
import ch.rupfizupfi.deck.testrunner.run.FinishCause;
import ch.rupfizupfi.deck.testrunner.run.RunControl;
import ch.rupfizupfi.deck.testrunner.run.Verdict;

import java.util.Objects;

/**
 * The turn of a cyclic run, shared by {@link CyclicProgram} and the run phase of
 * {@link TimeCyclicProgram}: the reversal, the limit adaptation from the envelope, and the cycle
 * count. Loop thread only.
 */
final class CyclicTurns {
    /** The turn forces as configured, newton; an adapted limit never passes them. */
    private final double targetUpper;
    private final double targetLower;
    /** The upper shut-off threshold, newton: the ceiling no cyclic run may pass. */
    private final double ceiling;
    private int cycleCount;

    CyclicTurns(TestParameter parameter) {
        this.targetUpper = parameter.upperTurnForce * 1000;
        this.targetLower = parameter.lowerTurnForce * 1000;
        this.ceiling = parameter.upperShutOffThreshold * 1000;
        this.cycleCount = Objects.requireNonNull(parameter.cycleCount, "the cycle count is required");
    }

    /** The turn forces, and the upper shut-off threshold as the ceiling. */
    Limits initialLimits() {
        return Limits.withCeiling(targetUpper, targetLower, ceiling);
    }

    double targetUpper() {
        return targetUpper;
    }

    double targetLower() {
        return targetLower;
    }

    /** The start lines both cyclic types write, before their own. */
    void announce(RunControl control) {
        Limits limits = control.limits();
        control.log("upperTurnForce " + limits.upper() + " Newton");
        control.log("lowerTurnForce " + limits.lower() + " Newton");
        control.log("upperShutOffThreshold " + limits.ceiling() + " Newton");
        control.log("CycleCount " + cycleCount);
    }

    /**
     * No state guard of its own: the run loop acts on a crossing or a timed turn only while RUNNING.
     * <p>
     * Reverses first: everything else here (limit adaptation, the envelope reset, the log lines,
     * each a STOMP send plus a flushed file write) adds to the overshoot past the turn force when it
     * runs before the write (H-05). The envelope is snapshotted before the reversal, the reset posted
     * right after it. A repeat in the commanded direction does nothing, and a reversal that threw
     * leaves the commanded direction, so the next crossing retries.
     *
     * @return finish once the last cycle is counted
     */
    Verdict onTurn(Direction turn, RunControl control) {
        boolean cyclesDone = false;
        switch (turn) {
            case RELEASE: // upper limit triggered
                if (control.direction() == Direction.PULL) {
                    float minValue = control.envelope().min();
                    control.turn(Direction.RELEASE);
                    control.resetEnvelopeMin((float) targetUpper);

                    double diff = targetLower - minValue;
                    if (diff != 0.0) {
                        Limits limits = control.limits();
                        control.adaptLimits(limits.upper(), Math.max(limits.lower() + diff, targetLower));
                    }

                    control.log("change direction to forward");
                    control.log("Current min value " + minValue);
                    if (diff != 0.0) {
                        control.log("New lower limit " + control.limits().lower());
                    }
                    control.log("CycleCount " + cycleCount);
                }
                break;
            case PULL:
                if (control.direction() == Direction.RELEASE) {
                    float maxValue = control.envelope().max();
                    control.turn(Direction.PULL);
                    control.resetEnvelopeMax((float) targetLower);

                    double diff = targetUpper - maxValue;
                    if (diff != 0.0) {
                        Limits limits = control.limits();
                        control.adaptLimits(Math.min(limits.upper() + diff, targetUpper), limits.lower());
                    }
                    int counted = cycleCount;
                    cycleCount--;
                    cyclesDone = cycleCount < 1;

                    control.log("change direction to backword");
                    control.log("Current max value " + maxValue);
                    if (diff != 0.0) {
                        control.log("New upper limit " + control.limits().upper());
                    }
                    control.log("CycleCount " + counted);
                }
                break;
        }
        return cyclesDone ? Verdict.finish(FinishCause.CYCLES_DONE) : Verdict.CONTINUE;
    }

    int cycleCount() {
        return cycleCount;
    }
}
