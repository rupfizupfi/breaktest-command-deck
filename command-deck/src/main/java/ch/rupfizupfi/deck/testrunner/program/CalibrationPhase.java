package ch.rupfizupfi.deck.testrunner.program;

import ch.rupfizupfi.deck.rig.RigLimits;
import ch.rupfizupfi.deck.testrunner.run.Direction;
import ch.rupfizupfi.deck.testrunner.run.Limit;
import ch.rupfizupfi.deck.testrunner.run.RunControl;
import ch.rupfizupfi.deck.testrunner.run.Verdict;
import jakarta.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.LongSupplier;

/**
 * The time-cyclic calibration, run at {@link #calibrationSpeed(RigLimits)}: every crossing reverses, and a turn
 * whose envelope reached the turn force it reverses at (within {@link #FORCE_THRESHOLD}) starts
 * timing that leg and ends the other. Once both legs have a time, the next turn hands over to a
 * {@link TimedCyclePhase} with both times scaled to the run speed, switching the drive to the run
 * speed and ramp after that turn's reversal.
 * <p>
 * The logic of {@code TimeCyclicTest}'s analyse run as is, with {@code AnalyseData} folded into
 * {@link Leg}. Loop thread only.
 */
final class CalibrationPhase implements Phase {
    private static final Logger logger = LoggerFactory.getLogger(CalibrationPhase.class);

    /**
     * mm/min, like {@code TestParameter.speed}: the nominal calibration speed, lowered to the rig's
     * maximum where that is slower ({@link #calibrationSpeed(RigLimits)}).
     */
    static final int ANALYSE_SPEED = 50;
    /** Newton: how close to a turn force the envelope must have come for the leg to count. */
    static final int FORCE_THRESHOLD = 300;

    /** One leg's travel time; 0 is unset, as the epoch-millis clock never reads it. */
    private static final class Leg {
        long startTime;
        long endTime;

        /** The first start counts until the leg is measured again from scratch. */
        void start(long now) {
            if (startTime == 0) {
                startTime = now;
                endTime = 0;
            }
        }

        boolean measured() {
            return startTime != 0 && endTime != 0;
        }

        long duration() {
            return endTime - startTime;
        }
    }

    private final CyclicTurns turns;
    /** mm/min the legs are measured at; the scaling multiplies by it. */
    private final double calibrationSpeed;
    /** mm/min; the scaling divides by it. */
    private final int runSpeed;
    private final int runRpm;
    private final @Nullable Ramp runRamp;
    private final LongSupplier clock;
    private final Leg release = new Leg();
    private final Leg pull = new Leg();

    /** @param calibrationSpeed mm/min the drive runs at during calibration; see {@link #calibrationSpeed(RigLimits)} */
    CalibrationPhase(CyclicTurns turns, double calibrationSpeed, int runSpeed, int runRpm, @Nullable Ramp runRamp,
                     LongSupplier clock) {
        this.turns = turns;
        this.calibrationSpeed = calibrationSpeed;
        this.runSpeed = runSpeed;
        this.runRpm = runRpm;
        this.runRamp = runRamp;
        this.clock = clock;
    }

    /**
     * {@link #ANALYSE_SPEED}, clamped to the run's rig maximum (owner decision): a rig slower than
     * the nominal calibration speed calibrates at its own top speed, and the travel times scale from
     * that instead.
     */
    static double calibrationSpeed(RigLimits rig) {
        return Math.min(ANALYSE_SPEED, rig.maxSpeedMillimetresPerMinute());
    }

    @Override
    public Phase onLimit(Limit limit, RunControl control) {
        return onTurn(limit.turn(), control);
    }

    /**
     * Nothing schedules a timed turn during calibration: one arriving here is from a phase that was
     * closed before this calibration began, and it is dropped.
     */
    @Override
    public Phase onTimedTurn(Direction next, RunControl control) {
        logger.info("dropping a timed turn to {} during calibration", next);
        return this;
    }

    /** Calibration turns never end the run; the cycle count runs only in the timed phase. */
    @Override
    public Verdict verdict() {
        return Verdict.CONTINUE;
    }

    /** Nothing to stop: calibration schedules nothing. */
    @Override
    public void close() {
    }

    private Phase onTurn(Direction turn, RunControl control) {
        if (release.measured() && pull.measured()) {
            return handOver(turn, control);
        }
        switch (turn) {
            case RELEASE -> releaseTurn(control);
            case PULL -> pullTurn(control);
        }
        return this;
    }

    /**
     * The handing-over turn is already the run's: the cyclic reversal first, then the run speed and
     * ramp, then the log lines. The timed phase starts with the next turn.
     */
    private Phase handOver(Direction turn, RunControl control) {
        long measuredReleaseMs = release.duration();
        long measuredPullMs = pull.duration();
        // Truncated, as the integer scaling at 50 mm/min was: exact there, so those runs are unchanged.
        long releaseMs = (long) (measuredReleaseMs * calibrationSpeed / runSpeed);
        long pullMs = (long) (measuredPullMs * calibrationSpeed / runSpeed);

        Verdict verdict = turns.onTurn(turn, control);
        control.runPhaseSpeed(runRpm, runRamp);
        control.log("Release time: " + measuredReleaseMs + " ms");
        control.log("Pull time: " + measuredPullMs + " ms");
        control.log("Adapted Release time: " + releaseMs + " ms");
        control.log("Adapted Pull time: " + pullMs + " ms");
        // Last, so a call above that threw leaves no scheduler thread behind.
        return new TimedCyclePhase(turns, control.timedTurns(), releaseMs, pullMs, verdict);
    }

    /** Snapshot, reverse, reset the envelope, then book-keep and log. */
    private void releaseTurn(RunControl control) {
        long now = clock.getAsLong();
        RunControl.Envelope envelope = control.envelope();
        float maxForceValue = envelope.max();
        float minForceValue = envelope.min();

        control.turn(Direction.RELEASE);
        control.resetEnvelopeMin((float) turns.targetUpper());

        boolean timed = Math.abs(maxForceValue - turns.targetUpper()) < FORCE_THRESHOLD;
        if (timed) {
            release.start(now);
            pull.endTime = now;
        }

        control.log("<b>init: start release round<b/>");
        if (timed) {
            control.log("set start time for release");
        }
        control.log("Current min value " + minForceValue);
    }

    /** Snapshot, reverse, reset the envelope, then book-keep and log. */
    private void pullTurn(RunControl control) {
        long now = clock.getAsLong();
        RunControl.Envelope envelope = control.envelope();
        float minForceValue = envelope.min();
        float maxForceValue = envelope.max();

        control.turn(Direction.PULL);
        control.resetEnvelopeMax((float) turns.targetLower());

        boolean timed = Math.abs(turns.targetLower() - minForceValue) < FORCE_THRESHOLD;
        if (timed) {
            pull.start(now);
            release.endTime = now;
        }

        control.log("<b>init: start pull round<b/>");
        if (timed) {
            control.log("set start time for pull");
        }
        control.log("Current max value " + maxForceValue);
    }
}
