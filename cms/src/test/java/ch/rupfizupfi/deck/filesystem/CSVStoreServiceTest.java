package ch.rupfizupfi.deck.filesystem;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CSVStoreServiceTest {

    private static final long RESULT_ID = 7L;

    @TempDir
    Path resultDataRoot;

    private CSVStoreService service;

    @BeforeEach
    void createFreshService() {
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

        // The Excel export reads every listed file as force data, so a log or sidecar in the
        // listing would contribute its lines to a run's peak.
        assertThat(service.listCSVFilesForTestResult(RESULT_ID)).containsExactly("1_force.csv");
    }

    @Test
    void listingForAMissingDirectoryIsEmptyNotNull() {
        assertThat(service.listCSVFilesForTestResult(999L)).isNotNull().isEmpty();
    }

    @Test
    void listingForAResultPathThatIsAFileIsEmptyNotNull() throws IOException {
        Files.writeString(resultDataRoot.resolve(String.valueOf(RESULT_ID)), "1000,2000\n");

        // The results grid and the project XLSX export both stream this array straight away.
        assertThat(service.listCSVFilesForTestResult(RESULT_ID)).isNotNull().isEmpty();
        assertThat(service.getPeaksFromResultFiles(RESULT_ID)).isEmpty();
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

    /** The peak of a finished run is a fact about the run, so an archived result still reports it. */
    @Test
    void peakIsFoundInAFileOlderThanFourDays() throws IOException {
        long fourHundredDaysAgo = System.currentTimeMillis() - 400L * 24 * 60 * 60 * 1000;
        writeForceCsvStartingAt("\n", fourHundredDaysAgo, 120, 250_000);

        assertThat(service.getPeaksFromResultFiles(RESULT_ID)).isEqualTo("250.0");
    }

    @Test
    void rowsWithNonNumericTimestampsAreSkipped() throws IOException {
        // A header carries no epoch millis in column 0, so it is not force data.
        Files.writeString(resultDirectory().resolve("1_force.csv"),
                "time,force\n" + forceCsvContent("\n", System.currentTimeMillis(), 120, 250_000));

        assertThat(service.getPeaksFromResultFiles(RESULT_ID)).isEqualTo("250.0");
    }

    @Test
    void fileWithFewerThan100LinesYieldsNoPeak() throws IOException {
        // A short file is a run that barely started; its noise must not become an Excel peak.
        writeForceCsv("\n", 99, 250_000);

        assertThat(service.getPeakFromResultFile(RESULT_ID, "1_force.csv")).isNull();
    }

    @Test
    void aStraySymbolLineDoesNotCostTheFileItsPeak() throws IOException {
        Files.writeString(resultDirectory().resolve("1_force.csv"),
                "@device-config\n" + forceCsvContent("\n", System.currentTimeMillis(), 120, 250_000));

        assertThat(service.getPeakFromResultFile(RESULT_ID, "1_force.csv")).isEqualTo("250.0");
    }

    @Test
    void rowsWithNonNumericForceAreSkipped() throws IOException {
        Files.writeString(resultDirectory().resolve("1_force.csv"),
                System.currentTimeMillis() + ",abc\n"
                        + forceCsvContent("\n", System.currentTimeMillis(), 120, 250_000));

        assertThat(service.getPeakFromResultFile(RESULT_ID, "1_force.csv")).isEqualTo("250.0");
    }

    @Test
    void readingAMissingFileReturnsEmptyNotAnException() {
        assertThat(service.readCSVDataForTestResult(RESULT_ID, "missing_force.csv")).isEmpty();
    }

    // The file name is caller-supplied: TestResultService.readCSVData hands it through from any
    // logged-in user, so containment is what keeps the read inside the result directory.
    @Test
    void readingATraversingNameIsRefused() {
        assertThatExceptionOfType(SecurityException.class)
                .isThrownBy(() -> service.readCSVDataForTestResult(RESULT_ID, "../8/1_force.csv"));
    }

    @Test
    void readingAnAbsoluteNameIsRefused() throws IOException {
        Path outside = Files.writeString(resultDataRoot.resolve("secret.txt"), "1000,2000\n");

        assertThatExceptionOfType(SecurityException.class)
                .isThrownBy(() -> service.readCSVDataForTestResult(RESULT_ID, outside.toString()));
    }

    @Test
    void readingAPlainNameReadsTheFile() throws IOException {
        Files.writeString(resultDirectory().resolve("1_force.csv"), "1000,2000\n");

        assertThat(service.readCSVDataForTestResult(RESULT_ID, "1_force.csv")).isEqualTo("1000,2000\n");
    }

    @Test
    void readingANameThatNormalizesBackInsideTheBaseReadsTheFile() throws IOException {
        Files.writeString(resultDirectory().resolve("1_force.csv"), "1000,2000\n");

        assertThat(service.readCSVDataForTestResult(RESULT_ID, "sub/../1_force.csv")).isEqualTo("1000,2000\n");
    }

    private Path resultDirectory() throws IOException {
        return Files.createDirectories(resultDataRoot.resolve(Long.toString(RESULT_ID)));
    }

    private void writeForceCsv(String lineEnding, int lines, long... milliNewtonValues) throws IOException {
        writeForceCsvStartingAt(lineEnding, System.currentTimeMillis(), lines, milliNewtonValues);
    }

    private void writeForceCsvStartingAt(String lineEnding, long firstMillis, int lines, long... milliNewtonValues)
            throws IOException {
        Files.writeString(resultDirectory().resolve("1_force.csv"),
                forceCsvContent(lineEnding, firstMillis, lines, milliNewtonValues));
    }

    /**
     * Lines of {@code <epoch-millis>,<milli-newtons>} one millisecond apart from
     * {@code firstMillis}. The first values come from {@code milliNewtonValues}, the rest are a
     * quiet 1000 (1 kN).
     */
    private static String forceCsvContent(String lineEnding, long firstMillis, int lines, long... milliNewtonValues) {
        StringBuilder csv = new StringBuilder();
        for (int i = 0; i < lines; i++) {
            long value = i < milliNewtonValues.length ? milliNewtonValues[i] : 1_000;
            csv.append(firstMillis + i).append(',').append(value).append(lineEnding);
        }
        return csv.toString();
    }
}
