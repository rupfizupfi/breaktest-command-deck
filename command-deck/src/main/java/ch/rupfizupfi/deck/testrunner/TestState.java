package ch.rupfizupfi.deck.testrunner;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Lifecycle of one test run. Persisted on the result row and broadcast to the browser, so the
 * constant names are part of both the audit record and the wire format — renaming one breaks
 * stored history.
 *
 * @see TestStateMachine for the transitions and who is allowed to make them
 */
public enum TestState {
    IDLE, STARTING, RUNNING, SENSOR_LOST, SAFE_HOLD, RESUMING, STOPPING, FINISHED, ABORTED, FAULT;

    /**
     * Legal successors. Every state can reach ABORTED and FAULT: an operator abort and an
     * unrecoverable hardware failure must never be refused by the state machine, whatever the run
     * was doing at the time.
     */
    private static final Map<TestState, Set<TestState>> TRANSITIONS = new EnumMap<>(TestState.class);

    static {
        TRANSITIONS.put(IDLE, EnumSet.of(STARTING, ABORTED, FAULT));
        TRANSITIONS.put(STARTING, EnumSet.of(RUNNING, ABORTED, FAULT));
        TRANSITIONS.put(RUNNING, EnumSet.of(SENSOR_LOST, STOPPING, FINISHED, ABORTED, FAULT));
        TRANSITIONS.put(SENSOR_LOST, EnumSet.of(SAFE_HOLD, ABORTED, FAULT));
        TRANSITIONS.put(SAFE_HOLD, EnumSet.of(RESUMING, ABORTED, FAULT));
        // RESUMING -> SENSOR_LOST is deliberate: a flapping link drops out again while the
        // reconnected stream is still being gated, and that second loss must re-enter the normal
        // loss path rather than fault the run.
        TRANSITIONS.put(RESUMING, EnumSet.of(RUNNING, SENSOR_LOST, ABORTED, FAULT));
        TRANSITIONS.put(STOPPING, EnumSet.of(FINISHED, ABORTED, FAULT));
        // Terminal states have no successors, which is what makes a repeated terminal transition a
        // no-op instead of an exception - AbstractTest.cleanup() runs twice by design.
        TRANSITIONS.put(FINISHED, EnumSet.noneOf(TestState.class));
        TRANSITIONS.put(ABORTED, EnumSet.noneOf(TestState.class));
        TRANSITIONS.put(FAULT, EnumSet.noneOf(TestState.class));
    }

    /** The run is over; nothing may move it again. */
    public boolean isTerminal() {
        return this == FINISHED || this == ABORTED || this == FAULT;
    }

    /** A sensor-loss incident is in progress: the motor is stopped and the operator is owed a banner. */
    public boolean isIncident() {
        return this == SENSOR_LOST || this == SAFE_HOLD || this == RESUMING;
    }

    public boolean canTransitionTo(TestState to) {
        return to != null && TRANSITIONS.get(this).contains(to);
    }
}
