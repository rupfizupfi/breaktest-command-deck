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
 * The log is an object rather than a bare array of transitions because the applied
 * {@link RecoveryGates} belong in it. The gates are a deployment fact — they decide whether a
 * sensor loss was survivable at all — and a transition list without them cannot be audited later,
 * when the deployment has moved on.
 * <p>
 * One instance per run: it owns the accumulated transition list.
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
     * Counted by the load-cell stream live at the transition; {@code Device.reset()} opens a new
     * stream at zero, so a SENSOR_LOST entry carries the dying stream's drops and a FINISHED entry
     * the last stream's.
     */
    private final LongSupplier droppedSampleCount;

    /**
     * The whole document is rewritten on every transition, so the transitions are accumulated here
     * and re-serialized. Reading the column back to append would mean a read-modify-write from the
     * measurement thread, the state machine's thread and the executor at once, and the loser of that
     * race silently drops transitions from the incident record.
     */
    private final List<TransitionEntry> transitions = new ArrayList<>();

    /**
     * @param persistExecutor MUST be single-threaded. Ordering is the point: the saves carry whole
     *                        documents and overwrite each other, so out-of-order execution would
     *                        leave the run showing an earlier state than it reached.
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
     * Serializes on the caller's thread but never persists on it. SENSOR_LOST is recorded by the
     * measurement thread while the motor is still coasting; a JPA call there blocks that thread for
     * the connection timeout whenever the deck points at a remote Postgres, which would delay the
     * incident handling itself. This is a safety property, not a throughput one.
     */
    @Override
    public void onTransition(TestStateMachine.TransitionRecord record) {
        try {
            RunStatus status = mapStatus(record.to());
            String document;
            synchronized (transitions) {
                transitions.add(new TransitionEntry(record.sequence(), name(record.from()),
                        name(record.to()), record.atEpochMillis(), record.reason(), record.actor()));
                // Snapshot taken under the same lock that appended, so the document a save carries
                // can never be missing the transition that triggered it.
                document = objectMapper.writeValueAsString(new LogDocument(
                        SCHEMA_VERSION, gates, List.copyOf(transitions), gapCount.getAsInt(),
                        droppedSampleCount.getAsLong()));
            }
            persistExecutor.execute(() -> persist(status, document));
        } catch (Exception e) {
            // Nothing here may escape into the listener fan-out: this runs on the incident path, and
            // a failed status write must never be able to abort the stop that is in flight.
            // writeValueAsString throws unchecked JacksonException in Jackson 3, and a shut-down
            // executor throws RejectedExecutionException; both land here.
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
            // Decided here and not at read time: the gap counter belongs to the run and is gone once
            // the process is.
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
