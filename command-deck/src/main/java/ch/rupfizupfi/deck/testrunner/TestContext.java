package ch.rupfizupfi.deck.testrunner;

import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;

public class TestContext {
    public static final int RELEASE_SIGNAL = 1;
    public static final int PULL_SIGNAL = 2;

    private volatile double upperLimit;
    private volatile double lowerLimit;
    private volatile int lastSendSignal = -1;
    private final long testResultId;
    private final List<SignalListener> signalListeners = new CopyOnWriteArrayList<>();
    private final BlockingQueue<Integer> signalQueue = new LinkedBlockingQueue<>();

    public TestContext(long testId, double upperLimit, double lowerLimit) {
        this.testResultId = testId;
        this.upperLimit = upperLimit;
        this.lowerLimit = lowerLimit;
    }

    public double getUpperLimit() {
        return upperLimit;
    }

    public double getLowerLimit() {
        return lowerLimit;
    }

    public void setLowerLimit(double lowerLimit) {
        this.lowerLimit = lowerLimit;
    }

    public void setUpperLimit(double upperLimit) {
        this.upperLimit = upperLimit;
    }

    public long getTestResultId() {
        return testResultId;
    }

    public void sendSignal(int signal) {
        if (signal == lastSendSignal) {
            return;
        }
        lastSendSignal = signal;
        signalQueue.offer(signal);
    }

    public void processSignals() throws InterruptedException, FinishTestException {
        while (true) {
            int signal = signalQueue.take();
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
