package ch.rupfizupfi.deck.testrunner.program;

import ch.rupfizupfi.deck.testrunner.measure.RunEventSink;
import ch.rupfizupfi.deck.testrunner.run.Direction;
import ch.rupfizupfi.deck.testrunner.run.RunControl;
import ch.rupfizupfi.deck.testrunner.run.RunEvent;
import jakarta.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * {@link RunControl} for the pure program tests: no threads, no drive. Every call a program makes
 * lands in {@link #calls} in order, log lines as {@code "log <line>"}, so a test can assert the
 * reversal came before the bookkeeping. Turns are accepted unless {@link #latched}, as
 * {@code RunLoop#turn} accepts them.
 */
class FakeRunControl implements RunControl {
    final List<String> calls = new ArrayList<>();
    final BlockingQueue<RunEvent> timedTurns = new LinkedBlockingQueue<>();

    boolean latched = false;
    @Nullable RuntimeException turnFailure;
    @Nullable RuntimeException rampFailure;
    Direction direction;
    float min;
    float max;
    float lastForce = Float.NaN;
    Limits limits;

    FakeRunControl(Limits limits) {
        this.limits = limits;
        this.min = (float) limits.lower();
        this.max = (float) limits.upper();
    }

    /** As the loop does once a setup's energize took. */
    FakeRunControl energized(DriveSetup setup) {
        direction = setup.direction();
        return this;
    }

    List<String> logLines() {
        return calls.stream().filter(c -> c.startsWith("log ")).map(c -> c.substring(4)).toList();
    }

    @Override
    public boolean turn(Direction next) {
        if (latched) {
            calls.add("turn refused " + next);
            return false;
        }
        if (turnFailure != null) {
            RuntimeException failure = turnFailure;
            turnFailure = null;
            throw failure;
        }
        calls.add("turn " + next);
        direction = next;
        return true;
    }

    @Override
    public @Nullable Direction direction() {
        return direction;
    }

    @Override
    public void runPhaseSpeed(int rpm, @Nullable Ramp ramp) {
        calls.add("runPhaseSpeed " + rpm + " " + ramp);
    }

    @Override
    public void disableSecondRamp() {
        calls.add("disableSecondRamp");
        if (rampFailure != null) {
            throw rampFailure;
        }
    }

    @Override
    public Envelope envelope() {
        calls.add("envelope");
        return new Envelope(min, max);
    }

    @Override
    public void resetEnvelopeMin(float value) {
        calls.add("resetEnvelopeMin " + value);
    }

    @Override
    public void resetEnvelopeMax(float value) {
        calls.add("resetEnvelopeMax " + value);
    }

    @Override
    public float lastForce() {
        return lastForce;
    }

    @Override
    public Limits limits() {
        return limits;
    }

    @Override
    public void adaptLimits(double upper, double lower) {
        limits = limits.adapted(upper, lower);
        calls.add("adaptLimits " + upper + " " + lower);
    }

    @Override
    public RunEventSink timedTurns() {
        return timedTurns::add;
    }

    @Override
    public void log(String line) {
        calls.add("log " + line);
    }
}
