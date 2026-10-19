package ch.rupfizupfi.deck.testrunner;

import ch.rupfizupfi.deck.data.RunStatus;
import ch.rupfizupfi.deck.data.TestResult;
import ch.rupfizupfi.deck.data.TestResultRepository;
import jakarta.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.function.IntSupplier;
import java.util.function.LongSupplier;

/**
 * Mirrors the run's state machine into {@code test_result}: {@link RunStatus} for querying, and a
 * JSON {@code interruptionLog} document for the post-incident read.
 * <p>
 * The log is an object rather than an array so it can carry the applied {@link RecoveryGates},
 * without which the transitions cannot be audited later.
 * <p>
 * One instance per run.
 */
public class TestResultStatusPersister implements TestStateListener {

    private static final Logger logger = LoggerFactory.getLogger(TestResultStatusPersister.class);

    /** Bumped when the document shape changes, so a reader can tell old rows apart. */
    private static final int SCHEMA_VERSION = 2;

    private final long testResultId;
    private final TestResultRepository repository;
    private final ObjectMapper objectMapper;
    private final Executor persistExecutor;
    private final @Nullable RecoveryGates gates;
    private final IntSupplier gapCount;

    /**
     * Per stream: {@code Device.reset()} opens a new stream at zero, so each entry carries the drops
     * of the stream live at that transition.
     */
    private final LongSupplier droppedSampleCount;

    /**
     * Accumulated here and re-serialized on every transition; reading the column back to append would
     * race between threads and drop transitions.
     */
    private final List<TransitionEntry> transitions = new ArrayList<>();

    /**
     * @param persistExecutor must be single-threaded: each save overwrites the whole document, so
     *                        out-of-order saves would regress the stored state
     */
    public TestResultStatusPersister(long testResultId,
                                     TestResultRepository repository,
                                     ObjectMapper objectMapper,
                                     Executor persistExecutor,
                                     @Nullable RecoveryGates gates,
                                     IntSupplier gapCount,
                                     LongSupplier droppedSampleCount) {
        this.testResultId = testResultId;
        this.repository = repository;
        this.objectMapper = objectMapper;
        this.persistExecutor = persistExecutor;
        this.gates = gates;
        this.gapCount = gapCount;
        this.droppedSampleCount = droppedSampleCount;
    }

    /**
     * Serializes on the caller's thread but never persists on it: SENSOR_LOST arrives on the
     * measurement thread, which a database timeout must not block.
     */
    @Override
    public void onTransition(TestStateMachine.TransitionRecord record) {
        try {
            RunStatus status = mapStatus(record.to());
            String document;
            synchronized (transitions) {
                transitions.add(new TransitionEntry(record.sequence(), name(record.from()),
                        name(record.to()), record.atEpochMillis(), record.reason(), record.actor()));
                // Under the append lock, so the document always includes its own transition.
                document = objectMapper.writeValueAsString(new LogDocument(
                        SCHEMA_VERSION, gates, List.copyOf(transitions), gapCount.getAsInt(),
                        droppedSampleCount.getAsLong()));
            }
            persistExecutor.execute(() -> persist(status, document));
        } catch (Exception e) {
            // Must not escape into the fan-out on the incident path. JacksonException (unchecked in
            // Jackson 3) and RejectedExecutionException both land here.
            logger.error("failed to record state transition {} for test result {}", record, testResultId, e);
        }
    }

    private void persist(@Nullable RunStatus status, String document) {
        try {
            TestResult result = repository.findById(testResultId).orElse(null);
            if (result == null) {
                logger.error("test result {} is gone, dropping its state transitions", testResultId);
                return;
            }
            if (status != null) {
                result.runStatus = status;
            }
            result.interruptionLog = document;
            repository.save(result);
        } catch (Exception e) {
            logger.error("failed to persist run status {} for test result {}", status, testResultId, e);
        }
    }

    /** Null means "leave the stored status alone", which is IDLE only — see {@link RunStatus}. */
    private @Nullable RunStatus mapStatus(TestState state) {
        return switch (state) {
            case STARTING, RUNNING, RESUMING, STOPPING -> RunStatus.IN_PROGRESS;
            case SENSOR_LOST, SAFE_HOLD -> RunStatus.INTERRUPTED;
            // Decided now: the gap counter is gone once the process is.
            case FINISHED -> gapCount.getAsInt() > 0 ? RunStatus.COMPLETED_WITH_GAPS : RunStatus.COMPLETED;
            case ABORTED -> RunStatus.ABORTED;
            case FAULT -> RunStatus.FAULT;
            case IDLE -> null;
        };
    }

    private static @Nullable String name(@Nullable TestState state) {
        return state == null ? null : state.name();
    }

    private record TransitionEntry(long seq, @Nullable String from, String to, long at,
                                   @Nullable String reason, @Nullable String actor) {
    }

    private record LogDocument(int schema, @Nullable RecoveryGates gates,
                               List<TransitionEntry> transitions, int gapCount,
                               long droppedSampleCount) {
    }
}
