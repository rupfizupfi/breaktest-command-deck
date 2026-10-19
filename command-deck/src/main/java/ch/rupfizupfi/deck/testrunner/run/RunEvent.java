package ch.rupfizupfi.deck.testrunner.run;

import ch.rupfizupfi.deck.testrunner.measure.SensorReconnector;

/**
 * The vocabulary a run reacts to on its {@link RunLoop}: the normal paths (step 5 of the OQ-64
 * migration) and the incident path (step 6). Every switch over this type has no default, so a
 * record added here does not compile until every switch names it.
 *
 * @see "doc/dev/backend/testrunner-architecture.md"
 */
public sealed interface RunEvent {

    /** The force went past a limit; emitted once per edge by the measurement loop. */
    record LimitCrossed(Limit limit, float force) implements RunEvent {
    }

    /** The time-cyclic run phase's scheduled reversal. */
    record TimedTurn(Direction next) implements RunEvent {
    }

    /**
     * The operator pressed Stop. Before enqueueing, the request thread committed the state and
     * latched the stop, touching no hardware.
     */
    record OperatorStop(String actor) implements RunEvent {
    }

    /**
     * An operator Abort or the safe-hold timeout. Before enqueueing, the machine was moved to
     * {@code ABORTED} and the motor stopped off the loop.
     */
    record Abort(String actor, String reason) implements RunEvent {
    }

    /** One plausible reading above the force ceiling. The measurement loop de-energized and flushed first. */
    record CeilingTripped(float force, double ceiling) implements RunEvent {
        /** The FAULT reason and run-log line, worded as they always were. */
        public String detail() {
            return "force " + force + " N exceeded the upper shut-off threshold " + ceiling + " N";
        }
    }

    /**
     * The measurement loop no longer watches the force: it died, or its inbound queue overflowed
     * because it fell that many batches behind the sensor. Either way the emitter de-energized
     * first, on its own thread.
     */
    record WatchdogFailed(String detail) implements RunEvent {
    }

    /**
     * The force trace could not be written, or its writer fell a full queue behind (OQ-64 step 9).
     * Not a safety event: the emitter touched nothing, and the loop ends the run {@code FAULT}
     * through its normal stop.
     */
    record PersistenceFailed(String detail) implements RunEvent {
    }

    /**
     * The measurement loop decided the force signal is gone. Before enqueueing it de-energized the
     * drive, flushed the pre-loss samples and opened the gap record (I3); {@link RunLoop#accept}
     * then commits {@code SENSOR_LOST} on that same thread.
     *
     * @param reason         which detector tripped and what it saw, for the log and the banner
     * @param lastKnownForce the last force considered valid, newton; the drift gate compares a
     *                       reconnected sensor against it
     */
    record SensorLost(String reason, float lastKnownForce) implements RunEvent {
    }

    /** The reconnector's verdict on one hold, from its worker thread. */
    record Recovered(SensorReconnector.Outcome outcome) implements RunEvent {
    }

    /**
     * The operator's Resume. Before enqueueing, the request thread moved the run
     * {@code SAFE_HOLD -> RESUMING} under the state lock, guarded by {@link ResumeGates}.
     */
    record ResumeAccepted(String actor) implements RunEvent {
    }

    /** The loop's own wake-up when a deadline passes: the hold expiry and the incident re-broadcast. */
    record Tick() implements RunEvent {
    }
}
