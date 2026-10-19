package ch.rupfizupfi.deck.testrunner.measure;

import ch.rupfizupfi.deck.testrunner.run.RunEvent;

/**
 * Where the measurement side hands what it detected to the run. Never blocks: an emitter on the
 * measurement thread must be back at its samples at once.
 */
@FunctionalInterface
public interface RunEventSink {
    void accept(RunEvent event);
}
