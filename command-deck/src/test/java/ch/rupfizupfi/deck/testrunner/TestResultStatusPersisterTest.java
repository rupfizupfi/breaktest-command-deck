package ch.rupfizupfi.deck.testrunner;

import ch.rupfizupfi.deck.data.RunStatus;
import ch.rupfizupfi.deck.data.TestResult;
import ch.rupfizupfi.deck.data.TestResultRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TestResultStatusPersisterTest {

    private static final long RESULT_ID = 42L;

    private TestResultRepository repository;
    private TestResult result;

    @BeforeEach
    void stubRepository() {
        repository = mock(TestResultRepository.class);
        result = new TestResult();
        when(repository.findById(RESULT_ID)).thenReturn(Optional.of(result));
    }

    private TestResultStatusPersister persister(Executor executor, IntSupplier gapCount) {
        return new TestResultStatusPersister(RESULT_ID, repository, new ObjectMapper(), executor,
                new RecoveryGates(10_000, List.of(1_000L), 60_000, 30_000, 100, 0.02, 2, 1_000, true),
                gapCount);
    }

    private static TestStateMachine.TransitionRecord record(TestState from, TestState to) {
        return new TestStateMachine.TransitionRecord(1, from, to, System.currentTimeMillis(), "test", null);
    }

    @ParameterizedTest
    @EnumSource(value = TestState.class, names = {"FINISHED", "IDLE"}, mode = EnumSource.Mode.EXCLUDE)
    void everyStateMapsToItsRunStatus(TestState state) {
        // Exhaustive switch, no default: a new TestState fails compilation here until its
        // RunStatus mapping is decided. FINISHED and IDLE have dedicated tests below.
        RunStatus expected = switch (state) {
            case STARTING, RUNNING, RESUMING, STOPPING -> RunStatus.IN_PROGRESS;
            case SENSOR_LOST, SAFE_HOLD -> RunStatus.INTERRUPTED;
            case ABORTED -> RunStatus.ABORTED;
            case FAULT -> RunStatus.FAULT;
            case FINISHED, IDLE -> throw new IllegalStateException("covered by dedicated tests");
        };

        persister(Runnable::run, () -> 0).onTransition(record(TestState.IDLE, state));

        verify(repository).save(result);
        assertThat(result.runStatus).isEqualTo(expected);
        assertThat(result.interruptionLog).contains(state.name());
    }

    @Test
    void finishedWithoutGapsIsCompleted() {
        persister(Runnable::run, () -> 0).onTransition(record(TestState.STOPPING, TestState.FINISHED));
        assertThat(result.runStatus).isEqualTo(RunStatus.COMPLETED);
    }

    @Test
    void finishedWithGapsIsCompletedWithGaps() {
        persister(Runnable::run, () -> 3).onTransition(record(TestState.STOPPING, TestState.FINISHED));
        assertThat(result.runStatus).isEqualTo(RunStatus.COMPLETED_WITH_GAPS);
    }

    @Test
    void gapVerdictIsDecidedAtTransitionTimeNotAtPersistTime() {
        AtomicInteger gaps = new AtomicInteger(1);
        List<Runnable> deferred = new ArrayList<>();

        persister(deferred::add, gaps::get).onTransition(record(TestState.STOPPING, TestState.FINISHED));
        gaps.set(0);
        deferred.forEach(Runnable::run);

        assertThat(result.runStatus).isEqualTo(RunStatus.COMPLETED_WITH_GAPS);
    }

    @Test
    void idleLeavesTheStoredRunStatusUntouched() {
        result.runStatus = RunStatus.COMPLETED;

        persister(Runnable::run, () -> 0).onTransition(record(null, TestState.IDLE));

        verify(repository).save(result);
        assertThat(result.runStatus).isEqualTo(RunStatus.COMPLETED);
        assertThat(result.interruptionLog).isNotNull();
    }

    @Test
    @SuppressWarnings("unchecked")
    void interruptionLogIsAVersionedDocumentCarryingGatesAllTransitionsAndGapCount() {
        // The field names asserted here are the persisted schema - readers of old rows depend
        // on them.
        TestResultStatusPersister persister = persister(Runnable::run, () -> 5);
        persister.onTransition(new TestStateMachine.TransitionRecord(
                1, TestState.IDLE, TestState.STARTING, 1_000L, "operator start", "operator-1"));
        persister.onTransition(new TestStateMachine.TransitionRecord(
                2, TestState.STARTING, TestState.RUNNING, 2_000L, "ramp done", null));

        Map<String, Object> document = new ObjectMapper().readValue(result.interruptionLog, Map.class);

        // Numbers via Number.longValue(): whether the mapper hands back Integer or Long for an
        // untyped document is its business, the persisted value is not.
        assertThat(((Number) document.get("schema")).longValue()).isEqualTo(1);
        assertThat(((Number) document.get("gapCount")).longValue()).isEqualTo(5);

        // The gates keep the incident record readable once the deployment's configuration has
        // moved on - the document's central auditability claim.
        Map<String, Object> gates = (Map<String, Object>) document.get("gates");
        assertThat(gates).containsKeys("reconnectWindowMillis", "backoffMillis",
                "safeHoldTimeoutMillis", "maxHoldForResumeMillis", "plausibilityGateMillis",
                "driftFraction", "maxLossesPerRun", "minEnvelopeNewton", "resumeEnabled");
        assertThat(((Number) gates.get("reconnectWindowMillis")).longValue()).isEqualTo(10_000);
        assertThat(gates.get("resumeEnabled")).isEqualTo(true);

        // Both transitions accumulated, in order - the document is rewritten whole each time.
        List<Map<String, Object>> transitions = (List<Map<String, Object>>) document.get("transitions");
        assertThat(transitions).hasSize(2);
        Map<String, Object> first = transitions.get(0);
        assertThat(((Number) first.get("seq")).longValue()).isEqualTo(1);
        assertThat(first.get("from")).isEqualTo("IDLE");
        assertThat(first.get("to")).isEqualTo("STARTING");
        assertThat(((Number) first.get("at")).longValue()).isEqualTo(1_000);
        assertThat(first.get("reason")).isEqualTo("operator start");
        assertThat(first.get("actor")).isEqualTo("operator-1");
        Map<String, Object> second = transitions.get(1);
        assertThat(((Number) second.get("seq")).longValue()).isEqualTo(2);
        assertThat(second.get("from")).isEqualTo("STARTING");
        assertThat(second.get("to")).isEqualTo("RUNNING");
        assertThat(second.get("actor")).isNull();
    }

    @Test
    void rejectedExecutorNeverEscapesOnTransition() {
        Executor shutDown = task -> {
            throw new RejectedExecutionException("executor is shut down");
        };

        assertThatCode(() -> persister(shutDown, () -> 0)
                .onTransition(record(TestState.RUNNING, TestState.ABORTED)))
                .doesNotThrowAnyException();

        verify(repository, never()).save(any());
    }
}
