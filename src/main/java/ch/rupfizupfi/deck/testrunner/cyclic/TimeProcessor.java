package ch.rupfizupfi.deck.testrunner.cyclic;

import ch.rupfizupfi.deck.testrunner.FinishTestException;
import ch.rupfizupfi.deck.testrunner.SignalListener;
import ch.rupfizupfi.deck.testrunner.TestContext;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class TimeProcessor implements SignalListener {
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
    private final CyclicTestContext testContext;
    private final long releaseTime;
    private final long pullTime;

    public TimeProcessor(CyclicTestContext testContext, long releaseTime, long pullTime) {
        this.testContext = testContext;
        this.releaseTime = releaseTime;
        this.pullTime = pullTime;
        this.testContext.addSignalListener(this);
    }

    public void start() {
        scheduler.scheduleAtFixedRate(this::sendReleaseSignal, 0, releaseTime, TimeUnit.MILLISECONDS);
        scheduler.scheduleAtFixedRate(this::sendPullSignal, releaseTime, pullTime, TimeUnit.MILLISECONDS);
    }

    private void sendReleaseSignal() {
        testContext.sendSignal(TestContext.RELEASE_SIGNAL);
    }

    private void sendPullSignal() {
        testContext.sendSignal(TestContext.PULL_SIGNAL);
    }

    public void stop() {
        testContext.removeSignalListener(this);
        scheduler.shutdown();
    }

    @Override
    public void handleSignal(int signal) throws FinishTestException {
        if (signal == TestContext.RELEASE_SIGNAL) {
            // send pull signal after release time
            scheduler.scheduleAtFixedRate(this::sendPullSignal, 0, releaseTime, TimeUnit.MILLISECONDS);
        }

        if (signal == TestContext.PULL_SIGNAL) {
            // send release signal after pull time
            scheduler.scheduleAtFixedRate(this::sendReleaseSignal, 0, pullTime, TimeUnit.MILLISECONDS);
        }
    }
}
