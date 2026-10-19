package ch.rupfizupfi.deck.testrunner.program;

import ch.rupfizupfi.deck.testrunner.run.RunControl;

import java.util.Objects;

/**
 * The time-cyclic resume: back into calibration on the calibration setup. The specimen creeps
 * during a hold, so travel times measured before the loss no longer apply.
 *
 * @param restart installs a fresh calibration phase; loop thread, like every phase change
 * @param setup   the calibration's energize, the same one the run started on
 */
record RestartCalibration(Runnable restart, DriveSetup setup) implements ResumePolicy {

    RestartCalibration {
        Objects.requireNonNull(restart, "restart");
        Objects.requireNonNull(setup, "setup");
    }

    @Override
    public boolean supportsResume() {
        return true;
    }

    @Override
    public DriveSetup resumeSetup(RunControl control) {
        restart.run();
        return setup;
    }
}
