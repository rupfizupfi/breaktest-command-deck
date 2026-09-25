package ch.rupfizupfi.deck.testrunner;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class TestStateTest {

    @ParameterizedTest
    @EnumSource(TestState.class)
    void everyStateAllowsExactlyItsAuditedSuccessors(TestState state) {
        // The transition map is the audit contract, so the FULL successor set is pinned per state.
        // Exhaustive switch, no default: a new constant must decide its successor set here.
        Set<TestState> expected = switch (state) {
            case IDLE -> EnumSet.of(TestState.STARTING, TestState.ABORTED, TestState.FAULT);
            case STARTING -> EnumSet.of(TestState.RUNNING, TestState.ABORTED, TestState.FAULT);
            case RUNNING -> EnumSet.of(TestState.SENSOR_LOST, TestState.STOPPING, TestState.FINISHED,
                    TestState.ABORTED, TestState.FAULT);
            case SENSOR_LOST -> EnumSet.of(TestState.SAFE_HOLD, TestState.ABORTED, TestState.FAULT);
            case SAFE_HOLD -> EnumSet.of(TestState.RESUMING, TestState.ABORTED, TestState.FAULT);
            case RESUMING -> EnumSet.of(TestState.RUNNING, TestState.SENSOR_LOST, TestState.ABORTED,
                    TestState.FAULT);
            case STOPPING -> EnumSet.of(TestState.FINISHED, TestState.ABORTED, TestState.FAULT);
            case FINISHED, ABORTED, FAULT -> EnumSet.noneOf(TestState.class);
        };

        Set<TestState> actual = Arrays.stream(TestState.values())
                .filter(state::canTransitionTo)
                .collect(Collectors.toCollection(() -> EnumSet.noneOf(TestState.class)));
        assertThat(actual).as("legal successors of %s", state).isEqualTo(expected);
    }

    @ParameterizedTest
    @EnumSource(TestState.class)
    void incidentStatesAreExactlyTheSensorLossPath(TestState state) {
        // isIncident() drives the operator banner. Exhaustive switch, no default: a new constant
        // must decide here whether it owes the operator a banner.
        boolean expected = switch (state) {
            case SENSOR_LOST, SAFE_HOLD, RESUMING -> true;
            case IDLE, STARTING, RUNNING, STOPPING, FINISHED, ABORTED, FAULT -> false;
        };
        assertThat(state.isIncident()).isEqualTo(expected);
    }

    @ParameterizedTest
    @EnumSource(value = TestState.class, names = {"FINISHED", "ABORTED", "FAULT"}, mode = EnumSource.Mode.EXCLUDE)
    void everyNonTerminalStateCanReachAbortedAndFault(TestState state) {
        assertThat(state.canTransitionTo(TestState.ABORTED)).as("%s -> ABORTED", state).isTrue();
        assertThat(state.canTransitionTo(TestState.FAULT)).as("%s -> FAULT", state).isTrue();
    }

    @ParameterizedTest
    @EnumSource(value = TestState.class, names = {"FINISHED", "ABORTED", "FAULT"})
    void terminalStatesRefuseEverySuccessor(TestState terminal) {
        assertThat(terminal.isTerminal()).isTrue();
        for (TestState to : TestState.values()) {
            assertThat(terminal.canTransitionTo(to)).as("%s -> %s", terminal, to).isFalse();
        }
    }

    @Test
    void resumingMayDropBackToSensorLost() {
        // A flapping link that drops out again mid-resume must re-enter the loss path, not fault.
        assertThat(TestState.RESUMING.canTransitionTo(TestState.SENSOR_LOST)).isTrue();
    }

    @ParameterizedTest
    @EnumSource(TestState.class)
    void nullIsNeverALegalSuccessor(TestState state) {
        assertThat(state.canTransitionTo(null)).isFalse();
    }
}
