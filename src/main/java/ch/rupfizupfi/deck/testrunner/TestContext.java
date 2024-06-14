package ch.rupfizupfi.deck.testrunner;

import java.util.ArrayList;
import java.util.List;

public class TestContext {
    private volatile double upperLimit;
    private volatile double lowerLimit;
    private long testId;
    private List<SignalListener> signalListeners;

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

    public long getTestId() {
        return testId;
    }

    void sendSignal(int signal){
        signalListeners.forEach(listener -> listener.handleSignal(signal));
    }

    void addSignalListener(SignalListener listener){
        signalListeners.add(listener);
    }
}
