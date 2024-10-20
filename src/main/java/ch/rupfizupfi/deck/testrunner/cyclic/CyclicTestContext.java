package ch.rupfizupfi.deck.testrunner.cyclic;

import ch.rupfizupfi.deck.testrunner.TestContext;

public class CyclicTestContext extends TestContext {
    int cycleCount;

    public CyclicTestContext(long testId, double upperLimit, double lowerLimit, int cycleCount) {
        super(testId, upperLimit, lowerLimit);
        this.cycleCount = cycleCount;
    }

    public void decrementCycleCount() {
        cycleCount--;
        if (cycleCount < 1) {
            sendSignal(0);
        }
    }

    public int getCycleCount() {
        return cycleCount;
    }
}
