package ch.rupfizupfi.deck.testrunner;

import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;

public class TestContext {
    public static final int RELEASE_SIGNAL = 1;
    public static final int PULL_SIGNAL = 2;

    /**
     * The wire value for a sensor loss. Documented here and DELIBERATELY NEVER ENQUEUED: every
     * consumer of this queue acts on whatever it is handed - {@code DestructiveTest#handleSignal}
     * finishes the run, {@code TimeCyclicTest#handleSignal} runs {@code analyze()} before it even
     * reaches its switch - so a value nobody is supposed to act on, sitting in a queue whose
     * consumers all act on it, is a loaded gun. {@link TestStateMachine} and
     * {@code /topic/test-state} carry that bookkeeping instead, typed and ordered. The constant
     * exists so the wire values stay documented.
     */
    public static final int SENSOR_LOST_SIGNAL = 3;

    private volatile double upperLimit;
    private volatile double lowerLimit;
    private volatile int lastSendSignal = -1;
    private volatile boolean dispatchEnabled = true;
    private final long testResultId;
    private final List<SignalListener> signalListeners = new CopyOnWriteArrayList<>();
    private final BlockingQueue<Integer> signalQueue = new LinkedBlockingQueue<>();

    public TestContext(long testId, double upperLimit, double lowerLimit) {
        this.testResultId = testId;
        this.upperLimit = requireFiniteLimit(upperLimit, "upper");
        this.lowerLimit = requireFiniteLimit(lowerLimit, "lower");
    }

    public double getUpperLimit() {
        return upperLimit;
    }

    public double getLowerLimit() {
        return lowerLimit;
    }

    public void setLowerLimit(double lowerLimit) {
        this.lowerLimit = requireFiniteLimit(lowerLimit, "lower");
    }

    public void setUpperLimit(double upperLimit) {
        this.upperLimit = requireFiniteLimit(upperLimit, "upper");
    }

    /**
     * A non-finite force limit is no shut-off at all: every comparison against NaN is false, so the
     * force checks stop firing and nothing commands the motor to reverse or stop. The cyclic tests
     * derive later limits from this one, so a poisoned value never washes out. Refusing it at the
     * boundary turns a silent loss of protection into a loud failure.
     */
    private static double requireFiniteLimit(double value, String which) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException(
                    "refusing a non-finite " + which + " force limit: " + value);
        }
        return value;
    }

    public long getTestResultId() {
        return testResultId;
    }

    /**
     * Signal 0 - stop - is exempt from the repeat filter. The watchdog sends 0 on sensor loss, which
     * would otherwise make the operator's own Stop button a duplicate and drop it on the floor at
     * the moment it is most likely to be pressed. A repeated stop costs nothing.
     */
    public void sendSignal(int signal) {
        if (signal != 0 && signal == lastSendSignal) {
            return;
        }
        lastSendSignal = signal;
        signalQueue.offer(signal);
    }

    /**
     * Clears the repeat filter. Without it a run whose last pre-loss signal was PULL and whose first
     * post-resume crossing is also PULL has that crossing swallowed as a duplicate: the motor pulls
     * on with nobody stopping it.
     */
    public void resetSignalDedup() {
        lastSendSignal = -1;
    }

    /** Drops queued crossings: after an incident they describe a force nobody is measuring any more. */
    public void drainSignals() {
        signalQueue.clear();
    }

    /** @see #processSignals() for what a disabled dispatch does, and what it still lets through */
    public void setSignalDispatchEnabled(boolean enabled) {
        this.dispatchEnabled = enabled;
    }

    public void processSignals() throws InterruptedException, FinishTestException {
        while (true) {
            int signal = signalQueue.take();
            // A stale crossing queued before the loss must not be replayed into a drive that is being
            // re-initialised. Signal 0 is exempt: the stop path must always get through.
            if (!dispatchEnabled && signal != 0) {
                continue;
            }
            for (SignalListener listener : signalListeners) {
                listener.handleSignal(signal);
            }
        }
    }

    public void addSignalListener(SignalListener listener) {
        signalListeners.add(listener);
    }

    public void removeSignalListener(SignalListener listener) {
        signalListeners.remove(listener);
    }
}
