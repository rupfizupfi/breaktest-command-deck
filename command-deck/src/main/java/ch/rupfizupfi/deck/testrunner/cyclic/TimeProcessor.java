package ch.rupfizupfi.deck.testrunner.cyclic;

import ch.rupfizupfi.deck.testrunner.FinishTestException;
import ch.rupfizupfi.deck.testrunner.SignalListener;
import ch.rupfizupfi.deck.testrunner.TestContext;

import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
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

    private void sendReleaseSignal() {
        testContext.sendSignal(TestContext.RELEASE_SIGNAL);
    }

    private void sendPullSignal() {
        testContext.sendSignal(TestContext.PULL_SIGNAL);
    }

    /**
     * Ends this processor for good; there is no counterpart. A scheduler cannot be restarted after
     * {@code shutdownNow()}, so a resumed run gets a NEW processor - see
     * {@code TimeCyclicTest#reinitDriveForResume}.
     */
    public void stop() {
        testContext.removeSignalListener(this);
        // shutdownNow, not shutdown: a scheduled direction change still pending would fire into a
        // drive that a safe stop just de-energized, or into one that is being re-energized.
        scheduler.shutdownNow();
    }

    @Override
    public void handleSignal(int signal) throws FinishTestException {
        if (signal == TestContext.RELEASE_SIGNAL) {
            schedule(this::sendPullSignal, releaseTime);
        }

        if (signal == TestContext.PULL_SIGNAL) {
            schedule(this::sendReleaseSignal, pullTime);
        }
    }

    private void schedule(Runnable task, long delayMillis) {
        try {
            scheduler.schedule(task, delayMillis, TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException e) {
            // stop() can land between the signal fan-out reading the listener list and this call.
            // The rejection would otherwise travel out of TestContext.processSignals() and tear down
            // a run that is only meant to be holding.
        }
    }
}
