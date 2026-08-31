package ch.rupfizupfi.deck.filesystem;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CSVStoreServiceTest {

    private static final long RESULT_ID = 7L;

    @TempDir
    Path resultDataRoot;

    private CSVStoreService service;

    @BeforeEach
    void createFreshService() {
        // Fresh instance per test: the production bean is a singleton with a mutable
        // minTimeStamp field, so shared state would let one test's peak scan taint another.
        StorageLocationService storageLocationService = mock(StorageLocationService.class);
        when(storageLocationService.getResultDataLocation()).thenReturn(resultDataRoot);
        service = new CSVStoreService(storageLocationService);
    }

    @Test
    void listingReturnsOnlyForceCsvFiles() throws IOException {
        Path directory = resultDirectory();
        Files.writeString(directory.resolve("1_force.csv"), "1000,2000\n");
        Files.writeString(directory.resolve("1_test.log"), "test started\n");
        Files.writeString(directory.resolve("1_gaps.json"), "{\"schema\":1}");
        // The .bak name CONTAINS the suffix without ending with it: the filter must be
        // endsWith, and a contains-based rewrite would let this backup into the export.
        Files.writeString(directory.resolve("1_force.csv.bak"), "1000,2000\n");

        // This filter is the only guard before the Excel export's unguarded
        // Double.parseDouble: a log or sidecar in the listing would reach it as force data.
        assertThat(service.listCSVFilesForTestResult(RESULT_ID)).containsExactly("1_force.csv");
    }

    @Test
    void listingForAMissingDirectoryIsEmptyNotNull() {
        assertThat(service.listCSVFilesForTestResult(999L)).isNotNull().isEmpty();
    }

    @Test
    void runFilesShareOneMillisPrefix() {
        CSVStoreService.TestRunFiles runFiles = service.generateRunFilesForTestResult(RESULT_ID);

        String forceName = Paths.get(runFiles.forceCsvPath()).getFileName().toString();
        String sidecarName = Paths.get(runFiles.gapsSidecarPath()).getFileName().toString();
        assertThat(forceName).endsWith("_force.csv");
        assertThat(sidecarName).endsWith("_gaps.json");

        // GapRecorder pairs the sidecar with the CSV whose gaps it explains purely by
        // name, so the prefix must be the same millis value taken once - two separate
        // currentTimeMillis() calls could straddle a tick.
        String forcePrefix = forceName.substring(0, forceName.length() - "_force.csv".length());
        String sidecarPrefix = sidecarName.substring(0, sidecarName.length() - "_gaps.json".length());
        assertThat(forcePrefix).isEqualTo(sidecarPrefix);
        assertThat(Long.parseLong(forcePrefix)).isPositive();

        Path expectedDirectory = resultDataRoot.resolve(Long.toString(RESULT_ID));
        assertThat(Paths.get(runFiles.forceCsvPath()).getParent()).isEqualTo(expectedDirectory);
        assertThat(Paths.get(runFiles.gapsSidecarPath()).getParent()).isEqualTo(expectedDirectory);
        assertThat(expectedDirectory).isDirectory();
    }

    /** A CSV may be read on a different OS than wrote it: both line endings must parse. */
    @Test
    void peakIsFoundInAUnixNewlineFile() throws IOException {
        writeForceCsv("\n", 120, 250_000);

        assertThat(service.getPeaksFromResultFiles(RESULT_ID)).isEqualTo("250.0");
    }

    @Test
    void peakIsFoundInAWindowsNewlineFile() throws IOException {
        writeForceCsv("\r\n", 120, 250_000);

        assertThat(service.getPeaksFromResultFiles(RESULT_ID)).isEqualTo("250.0");
    }

    @Test
    void peakScalesMilliNewtonsToKiloNewtonsAndCapsAt300() throws IOException {
        // 350_000 mN scales to 350 kN — above the 300 cap (the load cell tops out at 200 kN),
        // so it is discarded as garbage rather than reported as the peak.
        writeForceCsv("\n", 120, 350_000, 250_000);

        assertThat(service.getPeaksFromResultFiles(RESULT_ID)).isEqualTo("250.0");
    }

    @Test
    void fileWithFewerThan100LinesYieldsNoPeak() throws IOException {
        // A short file is a run that barely started; its noise must not become an Excel peak.
        writeForceCsv("\n", 99, 250_000);

        assertThat(service.getPeakFromResultFile(RESULT_ID, "1_force.csv")).isNull();
    }

    @Test
    void fileContainingAnAtSignYieldsNoPeak() throws IOException {
        // '@' marks a device-config preamble: the file is not plain force data, whatever its size.
        Files.writeString(resultDirectory().resolve("1_force.csv"),
                "@device-config\n" + forceCsvContent("\n", 120, 250_000));

        assertThat(service.getPeakFromResultFile(RESULT_ID, "1_force.csv")).isNull();
    }

    @Test
    void readingAMissingFileReturnsEmptyNotAnException() {
        assertThat(service.readCSVDataForTestResult(RESULT_ID, "missing_force.csv")).isEmpty();
    }

    private Path resultDirectory() throws IOException {
        return Files.createDirectories(resultDataRoot.resolve(Long.toString(RESULT_ID)));
    }

    private void writeForceCsv(String lineEnding, int lines, long... milliNewtonValues) throws IOException {
        Files.writeString(resultDirectory().resolve("1_force.csv"),
                forceCsvContent(lineEnding, lines, milliNewtonValues));
    }

    /**
     * Lines of {@code <epoch-millis>,<milli-newtons>}. Timestamps start at now, so they pass the
     * four-day recency window {@code getPeaksFromResultFiles} arms; the first values come from
     * {@code milliNewtonValues}, the rest are a quiet 1000 (1 kN).
     */
    private static String forceCsvContent(String lineEnding, int lines, long... milliNewtonValues) {
        StringBuilder csv = new StringBuilder();
        long now = System.currentTimeMillis();
        for (int i = 0; i < lines; i++) {
            long value = i < milliNewtonValues.length ? milliNewtonValues[i] : 1_000;
            csv.append(now + i).append(',').append(value).append(lineEnding);
        }
        return csv.toString();
    }
}
