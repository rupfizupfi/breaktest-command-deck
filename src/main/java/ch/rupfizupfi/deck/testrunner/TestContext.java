package ch.rupfizupfi.deck.testrunner;

public class TestContext {
    private volatile double upperLimit;
    private volatile double lowerLimit;
    private long testId;

    public TestContext(long testId, double upperLimit, double lowerLimit) {
        this.testId = testId;
        this.upperLimit = upperLimit;
        this.lowerLimit = lowerLimit;
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

    }
}
