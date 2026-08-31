package ch.rupfizupfi.deck.testrunner;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestStateMachineTest {

    private final TestStateMachine machine = new TestStateMachine();

    @Test
    void illegalTransitionReturnsFalseAndNeverThrows() {
        assertThatCode(() -> {
            assertThat(machine.transition(TestState.RUNNING, "skip STARTING")).isFalse();
        }).doesNotThrowAnyException();
        assertThat(machine.current()).isEqualTo(TestState.IDLE);
        assertThat(machine.history()).isEmpty();
    }

    @Test
    void repeatedTerminalTransitionIsANoOpNotAnException() {
        machine.transition(TestState.ABORTED, "first abort");
        assertThatCode(() -> {
            assertThat(machine.transition(TestState.ABORTED, "cleanup runs twice")).isFalse();
        }).doesNotThrowAnyException();
        assertThat(machine.history()).hasSize(1);
    }

    @Test
    void compareAndTransitionRefusesAStaleExpectedState() {
        assertThat(machine.compareAndTransition(TestState.RUNNING, TestState.STOPPING, "stale", null, () -> true))
                .isFalse();
        assertThat(machine.current()).isEqualTo(TestState.IDLE);
    }

    @Test
    void compareAndTransitionRefusesWhenTheGuardSaysNo() {
        assertThat(machine.compareAndTransition(TestState.IDLE, TestState.STARTING, "guarded", null, () -> false))
                .isFalse();
        assertThat(machine.current()).isEqualTo(TestState.IDLE);
        assertThat(machine.history()).isEmpty();
    }

    @Test
    void guardThatThrowsIsARefusalNotAnException() {
        assertThatCode(() -> {
            boolean applied = machine.compareAndTransition(TestState.IDLE, TestState.STARTING, "guard blows up",
                    null, () -> {
                        throw new IllegalStateException("boom");
                    });
            assertThat(applied).isFalse();
        }).doesNotThrowAnyException();
        assertThat(machine.current()).isEqualTo(TestState.IDLE);
    }

    @Test
    void sequenceNumbersAreMonotonicAcrossTransitions() {
        machine.transition(TestState.STARTING, "1");
        machine.transition(TestState.SAFE_HOLD, "refused, leaves no record");
        machine.transition(TestState.RUNNING, "2");
        machine.transition(TestState.STOPPING, "3");
        machine.transition(TestState.FINISHED, "4");

        List<TestStateMachine.TransitionRecord> history = machine.history();
        assertThat(history).hasSize(4);
        // The record javadoc promises commit order, nothing more - so assert strictly
        // increasing, not consecutive.
        for (int i = 1; i < history.size(); i++) {
            assertThat(history.get(i).sequence()).isGreaterThan(history.get(i - 1).sequence());
        }
    }

    @Test
    void historyRecordsCarryFromToReasonAndActor() {
        machine.transition(TestState.STARTING, "operator pressed start", "operator-1");
        machine.transition(TestState.RUNNING, "ramp-up finished");

        List<TestStateMachine.TransitionRecord> history = machine.history();
        TestStateMachine.TransitionRecord manual = history.getFirst();
        assertThat(manual.from()).isEqualTo(TestState.IDLE);
        assertThat(manual.to()).isEqualTo(TestState.STARTING);
        assertThat(manual.reason()).isEqualTo("operator pressed start");
        assertThat(manual.actor()).isEqualTo("operator-1");

        TestStateMachine.TransitionRecord automatic = history.get(1);
        assertThat(automatic.from()).isEqualTo(TestState.STARTING);
        assertThat(automatic.to()).isEqualTo(TestState.RUNNING);
        assertThat(automatic.reason()).isEqualTo("ramp-up finished");
        assertThat(automatic.actor()).as("machine-made transitions carry no actor").isNull();
    }

    @Test
    void historyIsImmutable() {
        machine.transition(TestState.STARTING, "start");
        List<TestStateMachine.TransitionRecord> history = machine.history();
        assertThatThrownBy(() -> history.add(history.getFirst()))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void throwingListenerDoesNotStopFanOutToLaterListeners() {
        List<String> invoked = new ArrayList<>();
        machine.addListener(record -> invoked.add("first"));
        machine.addListener(record -> {
            invoked.add("second");
            throw new IllegalStateException("listener boom");
        });
        machine.addListener(record -> invoked.add("third"));

        assertThat(machine.transition(TestState.STARTING, "fan-out")).isTrue();
        // Order-insensitive: every listener must hear the transition, but their invocation
        // order is incidental, not contract.
        assertThat(invoked).containsExactlyInAnyOrder("first", "second", "third");
    }

    @Test
    @Timeout(value = 5, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void listenerMayReenterTransitionFromItsCallbackWithoutDeadlock() {
        machine.addListener(record -> {
            if (record.to() == TestState.STARTING) {
                machine.transition(TestState.RUNNING, "re-entered from listener");
            }
        });

        assertThat(machine.transition(TestState.STARTING, "start")).isTrue();
        assertThat(machine.current()).isEqualTo(TestState.RUNNING);
        assertThat(machine.history()).hasSize(2);
    }
}
