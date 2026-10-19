package ch.rupfizupfi.deck.testrunner;

import ch.rupfizupfi.deck.filesystem.CSVStoreService;
import jakarta.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.ObjectMapper;

import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

/**
 * Records the holes a sensor loss punches in a run's force CSV into a {@code <millis>_gaps.json}
 * sidecar beside it. The CSV stays free of markers because external tools read it; there a gap is
 * only a jump in the epoch-millis column.
 * <p>
 * Gaps are opened by {@code measure.MeasurementLoop}, the only component that sees the samples stop.
 * <p>
 * The sidecar is created on the first gap, so a run that never loses the sensor leaves its result
 * directory untouched.
 */
public class GapRecorder {

    private static final Logger logger = LoggerFactory.getLogger(GapRecorder.class);

    /** Bumped when the document shape changes, so a reader can tell old sidecars apart. */
    private static final int SCHEMA_VERSION = 1;

    private final Path sidecar;
    private final Path directory;
    private final String forceFileName;
    private final long testResultId;
    private final RecoveryGates gates;
    private final ObjectMapper objectMapper;

    /** Accumulated in memory because every write rewrites the whole document - see {@link #write()}. */
    private final List<GapEntry> gaps = new ArrayList<>();
    private @Nullable String runOutcome;

    public GapRecorder(CSVStoreService.TestRunFiles runFiles, long testResultId, RecoveryGates gates,
                       ObjectMapper mapper) {
        this.sidecar = Paths.get(runFiles.gapsSidecarPath()).toAbsolutePath();
        this.directory = sidecar.getParent();
        this.forceFileName = Paths.get(runFiles.forceCsvPath()).getFileName().toString();
        this.testResultId = testResultId;
        this.gates = gates;
        this.objectMapper = mapper;
    }

    /**
     * Opens a gap and makes it durable immediately: an entry with {@code outcome:"HOLDING"} and a
     * null {@code endMillis} marks a run that died mid-hold.
     */
    public synchronized void beginGap(String reason, float lastKnownForce) {
        gaps.add(new GapEntry(gaps.size() + 1, reason, lastKnownForce, System.currentTimeMillis(),
                null, null, null, "HOLDING", null, null, null, null, null));
        write();
    }

    /** Closes the newest gap; see {@link #endGap(int, String, Float, Float, Double, int, String, Boolean)}. */
    public synchronized void endGap(String outcome, @Nullable Float firstForceAfter,
                                    @Nullable Float driftNewton,
                                    @Nullable Double driftFractionOfEnvelope, int reconnectAttempts,
                                    @Nullable String stopTier, @Nullable Boolean stopVerified) {
        endGap(gaps.size(), outcome, firstForceAfter, driftNewton, driftFractionOfEnvelope,
                reconnectAttempts, stopTier, stopVerified);
    }

    /**
     * Closes gap {@code index}, counting from 1. Not always the newest: a loss during a resume opens
     * the next gap before the run loop closes the one the resume belonged to.
     *
     * @param outcome {@code RESUMED}, {@code RESUME_FAILED}, {@code SUPERSEDED} (a further loss
     *                superseded this gap's resume; the next gap continues the incident), or the
     *                terminal state the run ended in while holding: {@code ABORTED | FAULT}. A gap
     *                still {@code HOLDING} marks a run that died mid-hold.
     */
    public synchronized void endGap(int index, String outcome, @Nullable Float firstForceAfter,
                                    @Nullable Float driftNewton,
                                    @Nullable Double driftFractionOfEnvelope, int reconnectAttempts,
                                    @Nullable String stopTier, @Nullable Boolean stopVerified) {
        if (index < 1 || index > gaps.size()) {
            logger.error("endGap({}) for test result {} with no gap {} open", outcome, testResultId, index);
            return;
        }

        GapEntry open = gaps.get(index - 1);
        if (open.endMillis() != null) {
            logger.error("endGap({}) for test result {}, but gap {} was already closed as {}",
                    outcome, testResultId, open.index(), open.outcome());
            return;
        }

        long end = System.currentTimeMillis();
        gaps.set(index - 1, new GapEntry(open.index(), open.reason(), open.lastKnownForceNewton(),
                open.startMillis(), end, end - open.startMillis(), reconnectAttempts, outcome,
                firstForceAfter, driftNewton, driftFractionOfEnvelope, stopTier, stopVerified));
        write();
    }

    /** No-op on a run that never had a gap. */
    public synchronized void recordRunOutcome(String outcome) {
        if (gaps.isEmpty()) {
            return;
        }
        runOutcome = outcome;
        write();
    }

    public synchronized int gapCount() {
        return gaps.size();
    }

    /**
     * Rewrites the whole document: temp file, {@link FileChannel#force(boolean)}, atomic replace, so
     * a crash during the incident never leaves a half-written sidecar.
     * <p>
     * Must stay compact, on one line: {@code CSVStoreService.getPeakFromResultFile} parses any file
     * of 100+ lines as force data, guarded only by the {@code *_force.csv} filter in
     * {@code listCSVFilesForTestResult}. Do not inject an indenting mapper.
     * <p>
     * Never throws: it runs on the incident path between the motor stop and the operator
     * notification. Failures are logged at ERROR.
     */
    private void write() {
        Path temp = null;
        try {
            String json = objectMapper.writeValueAsString(new Document(SCHEMA_VERSION, testResultId,
                    forceFileName, gates, List.copyOf(gaps), runOutcome));

            // Same directory as the sidecar: ATOMIC_MOVE is only guaranteed within one filesystem.
            temp = Files.createTempFile(directory, sidecar.getFileName().toString(), ".tmp");
            try (FileChannel channel = FileChannel.open(temp, StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING)) {
                ByteBuffer buffer = ByteBuffer.wrap(json.getBytes(StandardCharsets.UTF_8));
                while (buffer.hasRemaining()) {
                    channel.write(buffer);
                }
                // true: metadata too, before the rename publishes the file.
                channel.force(true);
            }

            Files.move(temp, sidecar, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            temp = null;
        } catch (Exception e) {
            // JacksonException (unchecked in Jackson 3) and IOException both land here.
            logger.error("failed to write the gap sidecar {} for test result {}", sidecar, testResultId, e);
        } finally {
            if (temp != null) {
                try {
                    Files.deleteIfExists(temp);
                } catch (Exception e) {
                    logger.error("failed to remove the partial gap sidecar {}", temp, e);
                }
            }
        }
    }

    private record GapEntry(int index, String reason, float lastKnownForceNewton, long startMillis,
                            @Nullable Long endMillis, @Nullable Long durationMillis,
                            @Nullable Integer reconnectAttempts, String outcome,
                            @Nullable Float firstForceAfterNewton, @Nullable Float driftNewton,
                            @Nullable Double driftFractionOfEnvelope, @Nullable String stopTier,
                            @Nullable Boolean stopVerified) {
    }

    /**
     * {@code forceFile} names the CSV the gaps belong to. The gates are included because a gap list
     * cannot be audited without the thresholds that decided it.
     */
    private record Document(int schema, long testResultId, String forceFile, RecoveryGates gates,
                            List<GapEntry> gaps, @Nullable String runOutcome) {
    }
}
