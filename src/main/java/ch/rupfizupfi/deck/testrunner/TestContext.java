package ch.rupfizupfi.deck.testrunner;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

public class TestContext {
    private volatile double upperLimit;
    private volatile double lowerLimit;
    private volatile int lastSendSignal = -1;
    private long testId;
    private List<SignalListener> signalListeners;
    private BlockingQueue<Integer> signalQueue = new LinkedBlockingQueue<>();

    public TestContext(long testId, double upperLimit, double lowerLimit) {
        this.testId = testId;
        this.upperLimit = upperLimit;
        this.lowerLimit = lowerLimit;
        this.signalListeners = new ArrayList<>();
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

    public long getTestId() {
        return testId;
    }

    public void sendSignal(int signal){
        if(signal == lastSendSignal){
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

    public void addSignalListener(SignalListener listener){
        signalListeners.add(listener);
    }
}
