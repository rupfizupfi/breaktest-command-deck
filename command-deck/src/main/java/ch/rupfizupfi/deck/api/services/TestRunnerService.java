package ch.rupfizupfi.deck.api.services;

import ch.rupfizupfi.deck.data.TestResult;
import ch.rupfizupfi.deck.data.User;
import ch.rupfizupfi.deck.security.AuthenticatedUser;
import ch.rupfizupfi.deck.testrunner.TestRunnerFactory;
import ch.rupfizupfi.deck.testrunner.TestRunnerThread;
import ch.rupfizupfi.deck.testrunner.TestState;
import ch.rupfizupfi.deck.testrunner.TestStateMessage;
import com.vaadin.hilla.BrowserCallable;
import jakarta.annotation.Nullable;
import jakarta.annotation.security.PermitAll;

/**
 * The browser's run controls: start, stop, the incident snapshot, and the two recovery commands.
 * <p>
 * {@code @PermitAll} covers Resume and Abort as it covers Stop: Abort must never be harder to reach
 * than Stop. Narrowing any of them is one decision for all four.
 */
@BrowserCallable
@PermitAll
public class TestRunnerService {
    private final TestRunnerThread testRunnerThread;
    private final TestResultService testResultService;
    private final AuthenticatedUser authenticatedUser;

    public TestRunnerService(TestResultService testResultService, TestRunnerFactory testRunnerFactory,
                             AuthenticatedUser authenticatedUser) {
        this.testResultService = testResultService;
        this.authenticatedUser = authenticatedUser;
        this.testRunnerThread = testRunnerFactory.createTestRunnerThread();
    }

    /**
     * Starts a run of a result the caller owns. A result that has run before is refused: starting it
     * again would overwrite its force trace, and a late call from a view that only meant to watch a
     * run must never re-energize the motor for a specimen that is already broken.
     */
    public void start(Long testId) {
        // Owner-scoped: an unscoped findById let any user run the rig against another tenant's result.
        TestResult testResult = testResultService.get(testId)
                .orElseThrow(() -> new IllegalArgumentException("Test result " + testId + " not found"));
        if (testResult.runStatus != null && !testRunnerThread.isActiveRun(testId)) {
            throw new IllegalStateException("Test result " + testId + " has already run (" + testResult.runStatus
                    + "); copy it to run the parameters again");
        }
        testRunnerThread.startThread(testResult);
    }

    /**
     * Carries the whole {@code /topic/test-state} snapshot, so a browser opened mid-incident need not
     * wait for the next broadcast.
     */
    public StatusResponse status() {
        TestStateMessage snapshot = testRunnerThread.snapshot();
        if (!testRunnerThread.isRunning() || snapshot == null) {
            return StatusResponse.idle();
        }
        return new StatusResponse(testRunnerThread.getTestResult(), snapshot);
    }

    /** The safety Stop: ends whatever runs on the bench, whichever view it was pressed in. */
    public void stop() {
        testRunnerThread.stopThread();
    }

    /**
     * Stops the run only if it is this result's, so closing a stale view can never end a newer run
     * someone else started since.
     *
     * @return whether that run was active and got the stop
     */
    public boolean stopRun(long testResultId) {
        if (!testRunnerThread.isActiveRun(testResultId)) {
            return false;
        }
        testRunnerThread.stopThread();
        return true;
    }

    /** Re-arms the watchdog and re-energizes the drive after a sensor loss; refusals carry a reason. */
    public TestRunnerThread.ResumeResponse resume() {
        return testRunnerThread.resumeTest(actor());
    }

    public TestRunnerThread.ResumeResponse abort() {
        return testRunnerThread.abortTest(actor());
    }

    /**
     * Who pressed the button, for the audit record. Falls back to a placeholder so an unresolved
     * session never refuses a resume or abort.
     */
    private String actor() {
        return authenticatedUser.get().map(User::getUsername).orElse("unknown operator");
    }

    public static class StatusResponse {
        public boolean isRunning;
        public @Nullable TestResult testResult;

        /**
         * The enum, not its name: Hilla generates a TS union, so the banner's switch is exhaustive.
         */
        public TestState state;
        public @Nullable String reason;
        /** The server's verdict, already gated. The UI renders it and must not compute its own. */
        public boolean canResume;
        public int lossCount;
        public int reconnectAttempt;

        /**
         * Boxed and nullable, so "no reading" and "not holding" are not reported as 0.
         */
        public @Nullable Float lastKnownForce;
        public @Nullable Long safeHoldDeadlineMillis;

        /**
         * Transition ordering, as in the broadcast frames, so the client can discard a status() answer
         * older than a frame it has already applied.
         */
        public long sequence;

        StatusResponse(@Nullable TestResult testResult, TestStateMessage snapshot) {
            this.isRunning = true;
            this.testResult = testResult;
            this.state = snapshot.state();
            this.reason = snapshot.reason();
            this.canResume = snapshot.canResume();
            this.lossCount = snapshot.lossCount();
            this.reconnectAttempt = snapshot.reconnectAttempt();
            this.lastKnownForce = snapshot.lastKnownForce();
            this.safeHoldDeadlineMillis = snapshot.safeHoldDeadlineMillis();
            this.sequence = snapshot.sequence();
        }

        private StatusResponse() {
            this.isRunning = false;
            this.state = TestState.IDLE;
        }

        /** IDLE rather than the last run's terminal state. */
        static StatusResponse idle() {
            return new StatusResponse();
        }
    }
}
