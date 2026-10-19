package ch.rupfizupfi.deck.testrunner.program;

import ch.rupfizupfi.deck.data.TestParameter;
import ch.rupfizupfi.deck.rig.RigLimits;
import ch.rupfizupfi.deck.testrunner.RunParameters;
import ch.rupfizupfi.deck.testrunner.run.Direction;
import ch.rupfizupfi.deck.testrunner.run.Limit;
import ch.rupfizupfi.deck.testrunner.run.RunControl;
import ch.rupfizupfi.deck.testrunner.run.Verdict;
import jakarta.annotation.Nullable;

import java.util.function.LongSupplier;

/**
 * A cyclic run whose turns, once calibrated, come on time (OQ-64 step 8): a {@link CalibrationPhase}
 * at the analyse speed measures the release and pull travel times between the turn forces, then a
 * {@link TimedCyclePhase} schedules every turn after the scaled time, the crossings still turning
 * the run too. Both phases share one {@link CyclicTurns}, so the limit adaptation and the cycle
 * count carry across a resume.
 * <p>
 * Every call, and so every phase change, is on the loop thread. A hold entry closes the phase -
 * after which nothing it scheduled can reach the run - and installs a fresh calibration; the loop
 * acts on no turn until the run is RUNNING again, and the resume calibrates once more. No stale
 * timed turn can reach a resumed run, and none needs a lock or an epoch to keep it out (H-15, I17).
 */
final class TimeCyclicProgram implements TestProgram {
    /** Tenths of a second, both ways: 300 ms. */
    private static final Ramp INITIAL_RAMP = new Ramp(3, 3);

    private final TestParameter parameter;
    /**
     * mm/min, the calibration's speed: {@link CalibrationPhase#ANALYSE_SPEED} (about 133 rpm; divided
     * by 375 before OQ-63, which commanded 0 rpm), clamped to the run's rig maximum.
     */
    private final double calibrationSpeed;
    private final CyclicTurns turns;
    private final int runRpm;
    private final @Nullable Ramp runRamp;
    private final LongSupplier clock;
    private final ResumePolicy resumePolicy;
    private Phase phase;

    /** @param rig the run's snapshot of the rig limits; its maximum speed caps the calibration speed */
    TimeCyclicProgram(TestParameter parameter, RigLimits rig) {
        this(parameter, rig, System::currentTimeMillis);
    }

    /** @param clock epoch millis, for the calibration's travel times */
    TimeCyclicProgram(TestParameter parameter, RigLimits rig, LongSupplier clock) {
        this.parameter = parameter;
        this.calibrationSpeed = CalibrationPhase.calibrationSpeed(rig);
        this.turns = new CyclicTurns(parameter);
        this.runRpm = RunParameters.toMotorRpm(parameter.speed);
        this.runRamp = Ramp.ofSeconds(parameter.startRampSeconds, parameter.stopRampSeconds);
        this.clock = clock;
        this.phase = calibration();
        this.resumePolicy = new RestartCalibration(this::recalibrate, startup());
    }

    @Override
    public String name() {
        return "TimeCyclicTest";
    }

    @Override
    public Limits limits() {
        return turns.initialLimits();
    }

    /** The calibration: releasing, at the analyse speed, on the short second ramp. */
    @Override
    public DriveSetup startup() {
        return new DriveSetup(RunParameters.toMotorRpm(calibrationSpeed), Direction.RELEASE, INITIAL_RAMP);
    }

    @Override
    public void onStart(RunControl control) {
        turns.announce(control);
        control.log("time cyclic test start");
        if (calibrationSpeed < CalibrationPhase.ANALYSE_SPEED) {
            control.log("Calibrating at " + millimetresPerMinute(calibrationSpeed) + " mm/min, the rig's maximum speed, instead of "
                    + CalibrationPhase.ANALYSE_SPEED + " mm/min");
        }
    }

    @Override
    public Verdict onLimit(Limit limit, RunControl control) {
        phase = phase.onLimit(limit, control);
        return phase.verdict();
    }

    @Override
    public Verdict onTimedTurn(Direction next, RunControl control) {
        phase = phase.onTimedTurn(next, control);
        return phase.verdict();
    }

    /** Back into calibration on the calibration setup: the specimen creeps during the hold. */
    @Override
    public ResumePolicy resumePolicy() {
        return resumePolicy;
    }

    /** Closes the timed phase's scheduler before SAFE_HOLD is committed (I17). */
    @Override
    public void onHoldEntry() {
        recalibrate();
    }

    /** The ramp reset first, as the cyclic teardown has it, then the phase. */
    @Override
    public void teardown(RunControl control) {
        CyclicProgram.resetSecondRamp(control);
        phase.close();
    }

    /** For tests: the phase the next turn reaches. */
    Phase phase() {
        return phase;
    }

    /** 30.0 as "30", 32.5 as "32.5". */
    private static String millimetresPerMinute(double speed) {
        return speed == Math.rint(speed) ? Long.toString((long) speed) : Double.toString(speed);
    }

    private void recalibrate() {
        phase.close();
        phase = calibration();
    }

    private CalibrationPhase calibration() {
        return new CalibrationPhase(turns, calibrationSpeed, parameter.speed, runRpm, runRamp, clock);
    }
}
