package ch.rupfizupfi.deck.testrunner.program;

import ch.rupfizupfi.deck.testrunner.run.RunControl;

/**
 * How a test type comes back from a sensor-loss hold (OQ-64 step 8): whether it resumes at all and
 * how it re-energizes. One value per program, from {@link TestProgram#resumePolicy()}.
 * <p>
 * Whether this particular hold may resume is not the policy's call: the gates (resume enabled, the
 * recovery verdict, the loss budget, the hold age, the drive handle) stay in {@code run.ResumeGates},
 * which asks {@link #supportsResume()} first. That check runs on request threads under the state
 * lock, so {@link #supportsResume()} must be a constant answer; {@link #resumeSetup} runs on the
 * loop thread only.
 */
public interface ResumePolicy {

    /** The run still enters SAFE_HOLD so the operator sees why it stopped, but only an abort ends it. */
    ResumePolicy ABORT_ONLY = new ResumePolicy() {
        @Override
        public boolean supportsResume() {
            return false;
        }

        @Override
        public DriveSetup resumeSetup(RunControl control) {
            throw new UnsupportedOperationException("abort-only: this test type does not resume after a sensor loss");
        }

        @Override
        public String toString() {
            return "ABORT_ONLY";
        }
    };

    /** Constant per program; any thread. */
    boolean supportsResume();

    /**
     * How the resumed run re-energizes, once the latch is cleared and just before the energize. Loop
     * thread; only asked when {@link #supportsResume()}.
     */
    DriveSetup resumeSetup(RunControl control);
}
