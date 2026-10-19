package ch.rupfizupfi.deck.device;

import ch.rupfizupfi.deck.data.TestResult;

/**
 * Readies the bench for one run before its startup checks open any device. Only the simulated
 * bench has something to do — it mounts a specimen matching the run's sample and test parameters —
 * so in real mode no bean exists and the runner skips the step.
 */
public interface BenchPreparation {

    /** Prepares the bench for {@code testResult}; returns one line for the run's test log. */
    String prepare(TestResult testResult);
}
