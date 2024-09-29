package ch.rupfizupfi.deck.testrunner.cyclic;

public class AnalyseData {
    public long startTime;
    public long endTime;
    public double minForce;
    public double maxForce;

    public void setAndCheckStartTime(long startTime) {
        if (this.startTime == 0) {
            this.startTime = startTime;
            this.endTime = 0;
        }
    }
}
