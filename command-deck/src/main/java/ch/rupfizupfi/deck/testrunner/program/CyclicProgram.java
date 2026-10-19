package ch.rupfizupfi.deck.testrunner.program;

import ch.rupfizupfi.deck.data.TestParameter;
import ch.rupfizupfi.deck.testrunner.RunParameters;
import ch.rupfizupfi.deck.testrunner.run.Direction;
import ch.rupfizupfi.deck.testrunner.run.Limit;
import ch.rupfizupfi.deck.testrunner.run.RunControl;
import ch.rupfizupfi.deck.testrunner.run.Verdict;

/**
 * Turns between the two turn forces for the configured number of cycles, bounded by the upper
 * shut-off threshold as the ceiling. Starts releasing, at the run speed and ramps.
 */
final class CyclicProgram implements TestProgram {
    private final CyclicTurns turns;
    private final int rpm;
    private final Ramp ramp;
    private final ResumePolicy resumePolicy;

    CyclicProgram(TestParameter parameter) {
        this.turns = new CyclicTurns(parameter);
        this.rpm = RunParameters.toMotorRpm(parameter.speed);
        this.ramp = Ramp.ofSeconds(parameter.startRampSeconds, parameter.stopRampSeconds);
        this.resumePolicy = new TowardNearerLimit(rpm, ramp);
    }

    @Override
    public String name() {
        return "CyclicTest";
    }

    @Override
    public Limits limits() {
        return turns.initialLimits();
    }

    @Override
    public DriveSetup startup() {
        return new DriveSetup(rpm, Direction.RELEASE, ramp);
    }

    @Override
    public void onStart(RunControl control) {
        turns.announce(control);
        control.log("cyclic test start");
    }

    @Override
    public Verdict onLimit(Limit limit, RunControl control) {
        return turns.onTurn(limit.turn(), control);
    }

    /** The same reversal a crossing calls for: only the time-cyclic run phase schedules them. */
    @Override
    public Verdict onTimedTurn(Direction next, RunControl control) {
        return turns.onTurn(next, control);
    }

    /** The run's own speed and ramp, heading toward the nearer limit. */
    @Override
    public ResumePolicy resumePolicy() {
        return resumePolicy;
    }

    /** After the loop's motor stop, so the ramp reset never comes ahead of the de-energize. */
    @Override
    public void teardown(RunControl control) {
        resetSecondRamp(control);
    }

    /** Cosmetic at this point: the safe stop may have closed the handle. */
    static void resetSecondRamp(RunControl control) {
        try {
            control.disableSecondRamp();
        } catch (RuntimeException e) {
            control.log("could not reset second ramp: " + e.getMessage());
        }
    }

    int cycleCount() {
        return turns.cycleCount();
    }
}
