package ch.rupfizupfi.deck.testrunner.program;

import ch.rupfizupfi.deck.data.Setting;
import ch.rupfizupfi.deck.data.SettingRepository;
import ch.rupfizupfi.deck.data.TestParameter;
import ch.rupfizupfi.deck.testrunner.RunParameters;
import ch.rupfizupfi.deck.testrunner.run.Direction;
import ch.rupfizupfi.deck.testrunner.run.FinishCause;
import ch.rupfizupfi.deck.testrunner.run.Limit;
import ch.rupfizupfi.deck.testrunner.run.MotorStop;
import ch.rupfizupfi.deck.testrunner.run.RunControl;
import ch.rupfizupfi.deck.testrunner.run.Verdict;

import java.util.Locale;
import java.util.function.IntConsumer;

/**
 * A single pull to the break: the shut-off thresholds are the limits and either crossing ends the
 * run. Only a break starts the suction job: the force fell below the lower shut-off after the
 * specimen had carried load, and the motor is known to have stopped (owner decision 2026-10-13).
 */
final class DestructiveProgram implements TestProgram {

    private final TestParameter parameter;
    /** Newton above the lower shut-off the peak must reach to count as built load; the run's rig-limit snapshot. */
    private final double suctionLoadMarginNewton;
    private final SettingRepository settings;
    /** Starts the suction job for that many seconds. */
    private final IntConsumer suction;
    /** The crossing that ended the run, null before one did. Loop thread only. */
    private Limit firedLimit;

    DestructiveProgram(TestParameter parameter, double suctionLoadMarginNewton, SettingRepository settings,
                       IntConsumer suction) {
        this.parameter = parameter;
        this.suctionLoadMarginNewton = suctionLoadMarginNewton;
        this.settings = settings;
        this.suction = suction;
    }

    @Override
    public String name() {
        return "DestructiveTest";
    }

    @Override
    public Limits limits() {
        return Limits.withoutCeiling(parameter.upperShutOffThreshold * 1000, parameter.lowerShutOffThreshold * 1000);
    }

    /** Pulling, on the first ramp: a previous cyclic run may have left the second ramp enabled. */
    @Override
    public DriveSetup startup() {
        return new DriveSetup(RunParameters.toMotorRpm(parameter.speed), Direction.PULL, null);
    }

    /**
     * Also resets the envelope maximum to zero: the measurement loop seeds it at the upper limit, and
     * the finish reads it as the run's peak force. Applied before the first sample is folded; this
     * runs before the energize, so nothing the specimen carries is missed.
     */
    @Override
    public void onStart(RunControl control) {
        control.resetEnvelopeMax(0);
        control.log("upperShutOffThreshold " + control.limits().upper() + " Newton");
        control.log("lowerShutOffThreshold " + control.limits().lower() + " Newton");
        control.log("Destructive test start");
    }

    /** Either shut-off crossing ends the run; which one decides the suction at the finish. */
    @Override
    public Verdict onLimit(Limit limit, RunControl control) {
        firedLimit = limit;
        return Verdict.finish(FinishCause.LIMIT);
    }

    /**
     * Abort-only: a blind coast-down voids the single-pull curve. The run still enters SAFE_HOLD so
     * the operator sees the reason.
     */
    @Override
    public ResumePolicy resumePolicy() {
        return ResumePolicy.ABORT_ONLY;
    }

    /**
     * Runs after the run loop stopped the motor (H-19): the settings read and the suction thread's
     * start used to run first, so the motor kept pulling past the shut-off for that long.
     * <p>
     * The suction starts only for a break: the run ended on the LOWER crossing, its peak force
     * reached the lower shut-off plus the suction load margin, and the motor stop was confirmed or
     * is coasting down. An abort, a trip or an operator stop never confirmed a break, and the loop
     * hands each over as its own cause, never as LIMIT. Every other LIMIT end is one run-log line
     * naming why the suction stayed off; the run still ends FINISHED.
     */
    @Override
    public void onFinished(FinishCause cause, MotorStop stop, RunControl control) {
        if (cause != FinishCause.LIMIT) {
            return;
        }
        String skipped = suctionSkipReason(stop, control);
        if (skipped != null) {
            control.log("suction skipped: " + skipped);
            return;
        }
        try {
            if (settings.getSettingValue(Setting.Key.TESTRUNNER_SUCK)) {
                suction.accept(settings.getSettingValue(Setting.Key.TESTRUNNER_SUCK_DURATION));
            }
        } catch (Exception e) {
            control.log("could not start the suction job: " + e.getMessage());
        }
    }

    /** Null for a break the suction may follow, else why it may not. */
    private String suctionSkipReason(MotorStop stop, RunControl control) {
        if (firedLimit != Limit.LOWER) {
            return "upper shut-off reached, specimen held";
        }
        float peak = control.envelope().max();
        double needed = control.limits().lower() + suctionLoadMarginNewton;
        if (!(peak >= needed)) {
            return String.format(Locale.ROOT, "no load built (peak %.1f N, a break needs %.1f N: the lower"
                    + " shut-off plus the suction load margin) - the test was not started correctly", peak, needed);
        }
        if (stop == MotorStop.UNCONFIRMED) {
            return "motor stop unconfirmed, use E-stop";
        }
        return null;
    }
}
