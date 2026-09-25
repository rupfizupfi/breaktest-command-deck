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
 * sidecar beside it. The CSV itself stays free of markers - external tools read it - so the gaps
 * are only visible there as a jump in the epoch-millis column; this file is what explains them.
 * <p>
 * Owned by {@link LoadCellThread}: it is the only component that sees the samples stop and start,
 * so it is the only one that can say where a gap begins and ends.
 * <p>
 * The sidecar is created ON THE FIRST GAP. A run that never loses the sensor must leave its result
 * directory exactly as it is today, so no consumer of that directory changes behaviour for the
 * runs that are the overwhelming majority.
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
     * Opens a gap and makes it durable immediately. This is the write that has to survive a crash:
     * an entry with {@code outcome:"HOLDING"} and a null {@code endMillis} is how a post-mortem
     * tells a run that died mid-hold from one that never lost the sensor at all.
     */
    public synchronized void beginGap(String reason, float lastKnownForce) {
        gaps.add(new GapEntry(gaps.size() + 1, reason, lastKnownForce, System.currentTimeMillis(),
                null, null, null, "HOLDING", null, null, null, null, null));
        write();
    }

    /**
     * @param outcome one of {@code HOLDING | RESUMED | ABORTED | FAULT}
     */
    public synchronized void endGap(String outcome, @Nullable Float firstForceAfter,
                                    @Nullable Float driftNewton,
                                    @Nullable Double driftFractionOfEnvelope, int reconnectAttempts,
                                    @Nullable String stopTier, @Nullable Boolean stopVerified) {
        if (gaps.isEmpty()) {
            logger.error("endGap({}) for test result {} with no gap open", outcome, testResultId);
            return;
        }

        GapEntry open = gaps.getLast();
        if (open.endMillis() != null) {
            logger.error("endGap({}) for test result {}, but gap {} was already closed as {}",
                    outcome, testResultId, open.index(), open.outcome());
            return;
        }

        long end = System.currentTimeMillis();
        gaps.set(gaps.size() - 1, new GapEntry(open.index(), open.reason(), open.lastKnownForceNewton(),
                open.startMillis(), end, end - open.startMillis(), reconnectAttempts, outcome,
                firstForceAfter, driftNewton, driftFractionOfEnvelope, stopTier, stopVerified));
        write();
    }

    /** No-op on a run that never had a gap, which is what keeps a clean run's directory untouched. */
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
     * Rewrites the whole document, temp file first, then {@link FileChannel#force(boolean)}, then an
     * atomic replace. A gap entry is written while the motor is coasting and the process may not
     * survive the incident, so a half-written or unflushed sidecar is the case worth engineering
     * against; rewriting whole also means no reader ever sees a partially appended array.
     * <p>
     * Written COMPACT, on one line, and it must stay that way: the export path reached by
     * {@code CSVStoreService.getPeakFromResultFile} parses any file of 100+ lines as force data, and
     * the {@code *_force.csv} filter in {@code listCSVFilesForTestResult} is the only thing standing
     * between a pretty-printed sidecar and an unguarded {@code Double.parseDouble}. The injected
     * mapper is Spring Boot's, which does not indent - do not hand this class an indenting one.
     * <p>
     * SAFETY INVARIANT: this method never throws. It runs on the incident path, between the motor
     * stop and the operator notification, so a failing sidecar write must not be able to break the
     * escalation it is describing. A failure is logged at ERROR and nothing else.
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
                // true: metadata too, so the bytes are on the platter before the rename publishes them.
                channel.force(true);
            }

            Files.move(temp, sidecar, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            temp = null;
        } catch (Exception e) {
            // writeValueAsString throws unchecked JacksonException in Jackson 3, the NIO calls throw
            // IOException; both land here, and neither may propagate.
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
     * {@code forceFile} names the CSV the gaps are holes in, so the sidecar stays readable after the
     * directory has collected several runs. The gates ride along for the same reason
     * {@code TestResultStatusPersister} carries them: they decide whether a loss was survivable, and
     * a gap list cannot be audited without them once the deployment has moved on.
     */
    private record Document(int schema, long testResultId, String forceFile, RecoveryGates gates,
                            List<GapEntry> gaps, @Nullable String runOutcome) {
    }
}
