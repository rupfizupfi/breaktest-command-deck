package ch.rupfizupfi.deck.testrunner;

import ch.rupfizupfi.deck.filesystem.CSVStoreService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class GapRecorderTest {

    private static final long RESULT_ID = 77L;
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {
    };

    private final ObjectMapper mapper = new ObjectMapper();

    @TempDir
    Path resultDirectory;

    private GapRecorder recorderIn(Path directory) {
        var runFiles = new CSVStoreService.TestRunFiles(
                directory.resolve("123_force.csv").toString(),
                directory.resolve("123_gaps.json").toString());
        return new GapRecorder(runFiles, RESULT_ID, gates(), mapper);
    }

    private static RecoveryGates gates() {
        return new RecoveryGates(10_000, List.of(1_000L), 60_000, 30_000, 100, 0.02, 2, 1_000, true);
    }

    private Path sidecar() {
        return resultDirectory.resolve("123_gaps.json");
    }

    private Map<String, Object> readSidecar() throws IOException {
        return mapper.readValue(Files.readString(sidecar()), MAP);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> gapsOf(Map<String, Object> document) {
        return (List<Map<String, Object>>) document.get("gaps");
    }

    /** Streams must be closed on Windows or the open handle makes @TempDir cleanup fail. */
    private static List<String> fileNamesIn(Path directory) throws IOException {
        try (Stream<Path> files = Files.list(directory)) {
            return files.map(path -> path.getFileName().toString()).toList();
        }
    }

    @Test
    void cleanRunWritesNothingToDisk() throws IOException {
        GapRecorder recorder = recorderIn(resultDirectory);
        recorder.recordRunOutcome("COMPLETED");

        // The clean-run promise: a run that never lost the sensor leaves its result
        // directory exactly as it was, so no consumer changes behaviour for those runs.
        assertThat(fileNamesIn(resultDirectory)).isEmpty();
    }

    @Test
    void beginGapDurablyWritesAHoldingEntryWithNoEnd() throws IOException {
        recorderIn(resultDirectory).beginGap("load cell stream ended", 12.5f);

        assertThat(sidecar()).exists();
        Map<String, Object> document = readSidecar();
        assertThat(((Number) document.get("schema")).intValue()).isEqualTo(1);
        assertThat(((Number) document.get("testResultId")).longValue()).isEqualTo(RESULT_ID);
        assertThat(document.get("forceFile")).isEqualTo("123_force.csv");
        assertThat(document.get("runOutcome")).isNull();

        List<Map<String, Object>> gaps = gapsOf(document);
        assertThat(gaps).hasSize(1);
        Map<String, Object> gap = gaps.getFirst();
        assertThat(((Number) gap.get("index")).intValue()).isEqualTo(1);
        assertThat(gap.get("reason")).isEqualTo("load cell stream ended");
        assertThat(((Number) gap.get("lastKnownForceNewton")).doubleValue()).isEqualTo(12.5);
        assertThat(((Number) gap.get("startMillis")).longValue()).isPositive();
        assertThat(gap.get("outcome")).isEqualTo("HOLDING");
        // Schema-1 wire shape: an open entry serializes endMillis as an explicit null, never
        // an absent key — readers depend on the shape, and the schema field versions it.
        assertThat(gap).containsKey("endMillis");
        assertThat(gap.get("endMillis")).isNull();
    }

    @Test
    void gatesAreCopiedVerbatimIntoTheSidecar() throws IOException {
        recorderIn(resultDirectory).beginGap("load cell stream ended", 12.5f);

        // The gates are the audit half of the document: the gap list can only be judged
        // against them once the deployment's config has moved on (see GapRecorder.Document).
        assertThat(readSidecar().get("gates")).isEqualTo(Map.of(
                "reconnectWindowMillis", 10_000,
                "backoffMillis", List.of(1_000),
                "safeHoldTimeoutMillis", 60_000,
                "maxHoldForResumeMillis", 30_000,
                "plausibilityGateMillis", 100,
                "driftFraction", 0.02,
                "maxLossesPerRun", 2,
                "minEnvelopeNewton", 1_000.0,
                "resumeEnabled", true));
    }

    @Test
    void closingAGapRecordsTheFullIncidentPayload() throws IOException {
        GapRecorder recorder = recorderIn(resultDirectory);
        recorder.beginGap("load cell stream ended", 12.5f);
        recorder.endGap("ABORTED", 12.75f, 0.25f, 0.004, 3, "tier-1", true);

        Map<String, Object> gap = gapsOf(readSidecar()).getFirst();
        assertThat(gap.get("outcome")).isEqualTo("ABORTED");
        long start = ((Number) gap.get("startMillis")).longValue();
        long end = ((Number) gap.get("endMillis")).longValue();
        assertThat(((Number) gap.get("durationMillis")).longValue()).isEqualTo(end - start);
        assertThat(((Number) gap.get("firstForceAfterNewton")).doubleValue()).isEqualTo(12.75);
        // driftNewton is the one payload field ResultViewer.tsx actually renders next to a gap.
        assertThat(((Number) gap.get("driftNewton")).doubleValue()).isEqualTo(0.25);
        assertThat(((Number) gap.get("driftFractionOfEnvelope")).doubleValue()).isEqualTo(0.004);
        assertThat(((Number) gap.get("reconnectAttempts")).intValue()).isEqualTo(3);
        assertThat(gap.get("stopTier")).isEqualTo("tier-1");
        assertThat(gap.get("stopVerified")).isEqualTo(Boolean.TRUE);
    }

    @Test
    void aSecondGapGetsIndexTwo() throws IOException {
        GapRecorder recorder = recorderIn(resultDirectory);
        recorder.beginGap("load cell stream ended", 12.5f);
        recorder.endGap("RESUMED", 12.9f, 0.4f, 0.004, 1, null, null);
        recorder.beginGap("load cell stream ended", 13.0f);

        List<Map<String, Object>> gaps = gapsOf(readSidecar());
        assertThat(gaps).hasSize(2);
        assertThat(((Number) gaps.getFirst().get("index")).intValue()).isEqualTo(1);
        Map<String, Object> second = gaps.getLast();
        assertThat(((Number) second.get("index")).intValue()).isEqualTo(2);
        assertThat(second.get("outcome")).isEqualTo("HOLDING");
    }

    @Test
    void sidecarIsOneCompactLineAndLeavesNoTempFile() throws IOException {
        GapRecorder recorder = recorderIn(resultDirectory);
        recorder.beginGap("load cell stream ended", 12.5f);
        recorder.endGap("RESUMED", 12.9f, 0.4f, 0.004, 2, null, null);
        recorder.recordRunOutcome("COMPLETED_WITH_GAPS");

        // Safety-adjacent: CSVStoreService.getPeakFromResultFile feeds any 100+-line file
        // to an unguarded Double.parseDouble, and the *_force.csv filter is the only other
        // guard. A pretty-printed sidecar would be one refactoring away from that path.
        String raw = Files.readString(sidecar());
        assertThat(raw).doesNotContain("\n").doesNotContain("\r");
        assertThat(Files.readAllLines(sidecar())).hasSize(1);

        // The temp file of the atomic write must never outlive the write.
        assertThat(fileNamesIn(resultDirectory))
                .allSatisfy(name -> assertThat(name).doesNotEndWith(".tmp"))
                .containsExactly("123_gaps.json");
    }

    @Test
    void recordRunOutcomeAfterAGapUpdatesTheDocument() throws IOException {
        GapRecorder recorder = recorderIn(resultDirectory);
        recorder.beginGap("load cell stream ended", 12.5f);
        recorder.recordRunOutcome("ABORTED");

        assertThat(readSidecar().get("runOutcome")).isEqualTo("ABORTED");
    }

    @Test
    void failingWriteNeverEscapes() {
        // Parent directory does not exist and is never created: every write() attempt fails.
        // write() runs on the incident path, between motor stop and operator notification,
        // so a failing sidecar write must not break the escalation it is describing.
        GapRecorder recorder = recorderIn(resultDirectory.resolve("missing").resolve("nested"));

        assertThatCode(() -> recorder.beginGap("load cell stream ended", 12.5f))
                .doesNotThrowAnyException();
        assertThatCode(() -> recorder.endGap("ABORTED", null, null, null, 3, "tier-1", true))
                .doesNotThrowAnyException();

        // The in-memory record survives the disk failure - a later write may still succeed.
        assertThat(recorder.gapCount()).isEqualTo(1);
    }

    @Test
    void endGapWithNoOpenGapIsIgnored() throws IOException {
        GapRecorder recorder = recorderIn(resultDirectory);

        assertThatCode(() -> recorder.endGap("RESUMED", 12.9f, 0.4f, 0.004, 1, null, null))
                .doesNotThrowAnyException();

        assertThat(fileNamesIn(resultDirectory)).isEmpty();
    }

    @Test
    void secondEndGapOnAClosedGapChangesNothing() throws IOException {
        GapRecorder recorder = recorderIn(resultDirectory);
        recorder.beginGap("load cell stream ended", 12.5f);
        recorder.endGap("RESUMED", 12.9f, 0.4f, 0.004, 2, null, null);
        byte[] afterFirstClose = Files.readAllBytes(sidecar());

        assertThatCode(() -> recorder.endGap("ABORTED", null, null, null, 5, "tier-2", false))
                .doesNotThrowAnyException();

        assertThat(Files.readAllBytes(sidecar())).isEqualTo(afterFirstClose);
        Map<String, Object> gap = gapsOf(readSidecar()).getFirst();
        assertThat(gap.get("outcome")).isEqualTo("RESUMED");
        assertThat(((Number) gap.get("endMillis")).longValue()).isPositive();
        assertThat(((Number) gap.get("reconnectAttempts")).intValue()).isEqualTo(2);
    }
}
