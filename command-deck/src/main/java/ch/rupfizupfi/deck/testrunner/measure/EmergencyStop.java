package ch.rupfizupfi.deck.testrunner.measure;

import ch.rupfizupfi.deck.testrunner.SafeStopResult;

/**
 * The de-energize a detector performs on its own thread before it tells the run anything (I3).
 * {@code MotorSafetyController#safeStop} is the one implementation: never throws, idempotent.
 */
@FunctionalInterface
public interface EmergencyStop {
    SafeStopResult stop(String reason);
}
