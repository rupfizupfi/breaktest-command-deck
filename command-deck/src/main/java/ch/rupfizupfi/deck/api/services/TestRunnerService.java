package ch.rupfizupfi.deck.api.services;

import ch.rupfizupfi.deck.data.TestResult;
import ch.rupfizupfi.deck.data.TestResultRepository;
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
 * {@code @PermitAll} covers Resume and Abort for the same reason it covers Stop: <b>Abort must never
 * be harder to reach than Stop.</b> Both end a run on a bench somebody is standing at, and a control
 * that answers "forbidden" while the motor is energized is worse than no control. Whether any of the
 * four should be narrowed is one decision for all of them, not something to settle here by giving the
 * newest command a different rule.
 */
@BrowserCallable
@PermitAll
public class TestRunnerService {
    private final TestRunnerThread testRunnerThread;
    private final TestResultRepository testResultRepository;
    private final AuthenticatedUser authenticatedUser;

    public TestRunnerService(TestResultRepository testResultRepository, TestRunnerFactory testRunnerFactory,
                             AuthenticatedUser authenticatedUser) {
        this.testResultRepository = testResultRepository;
        this.authenticatedUser = authenticatedUser;
        this.testRunnerThread = testRunnerFactory.createTestRunnerThread();
    }

    public void start(int testId) {
        testRunnerThread.startThread(testResultRepository.findById((long) testId).orElseThrow(() -> new RuntimeException("Test not found")));
    }

    /**
     * Carries the whole {@code /topic/test-state} snapshot, so a browser that opens mid-incident can
     * rebuild its banner from one HTTP read instead of waiting up to 2 s for the next broadcast.
     */
    public StatusResponse status() {
        TestStateMessage snapshot = testRunnerThread.snapshot();
        if (!testRunnerThread.isRunning() || snapshot == null) {
            return StatusResponse.idle();
        }
        return new StatusResponse(testRunnerThread.getTestResult(), snapshot);
    }

    public void stop() {
        testRunnerThread.stopThread();
    }

    /** Re-arms the watchdog and re-energizes the drive after a sensor loss; refusals carry a reason. */
    public TestRunnerThread.ResumeResponse resume() {
        return testRunnerThread.resumeTest(actor());
    }

    public TestRunnerThread.ResumeResponse abort() {
        return testRunnerThread.abortTest(actor());
    }

    /**
     * Who pressed the button, for the run's audit record. Falls back to a placeholder rather than
     * throwing: a resume or an abort must not be refused because the session could not be resolved.
     */
    private String actor() {
        return authenticatedUser.get().map(User::getUsername).orElse("unknown operator");
    }

    public static class StatusResponse {
        public boolean isRunning;
        public @Nullable TestResult testResult;

        /**
         * The enum, not its name: Hilla turns it into a TS union, which is what lets the banner
         * switch exhaustively and fail the typecheck when a state is added.
         */
        public TestState state;
        public @Nullable String reason;
        /** The server's verdict, already gated. The UI renders it and must not compute its own. */
        public boolean canResume;
        public int lossCount;
        public int reconnectAttempt;

        /**
         * Boxed and nullable throughout. Primitives would generate a non-nullable {@code number} and
         * report "no reading yet" as a measured 0 N, and "not holding" as a deadline in 1970.
         */
        public @Nullable Float lastKnownForce;
        public @Nullable Long safeHoldDeadlineMillis;

        /**
         * Transition ordering, the same counter the broadcast frames carry. The client needs it to
         * tell this read from a frame it has already applied - without it, a status() answer that
         * left the server before the loss would silently clear an incident banner.
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

        /** IDLE and not the last run's terminal state: a finished run is not something to annunciate. */
        static StatusResponse idle() {
            return new StatusResponse();
        }
    }
}
