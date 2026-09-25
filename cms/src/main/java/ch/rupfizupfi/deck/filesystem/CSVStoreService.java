package ch.rupfizupfi.deck.filesystem;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

@Service
public class CSVStoreService {
    private static final Logger log = LoggerFactory.getLogger(CSVStoreService.class);

    private static final String FORCE_CSV_SUFFIX = "_force.csv";
    private static final String GAPS_SIDECAR_SUFFIX = "_gaps.json";

    protected final StorageLocationService storageLocationService;

    public CSVStoreService(StorageLocationService storageLocationService) {
        this.storageLocationService = storageLocationService;
    }

    /**
     * The two files one run writes: the force CSV and the sidecar that records the gaps in it.
     * Both names carry the same millis prefix, taken once here, so they pair up by construction
     * rather than by reconstructing one name from the other. {@code gapsSidecarPath} is where a
     * sidecar would go - a run that never loses the sensor writes none.
     */
    public record TestRunFiles(String forceCsvPath, String gapsSidecarPath) {
    }

    public TestRunFiles generateRunFilesForTestResult(long testResultId) {
        Path directory = Paths.get(getBasePathForTestResult(testResultId));
        directory.toFile().mkdirs();
        long stamp = System.currentTimeMillis();
        return new TestRunFiles(directory.resolve(stamp + FORCE_CSV_SUFFIX).toString(),
                directory.resolve(stamp + GAPS_SIDECAR_SUFFIX).toString());
    }

    /** Every caller streams the result, so an unlistable result path yields an empty array. */
    public String[] listCSVFilesForTestResult(long testResultId) {
        Path path = Paths.get(getBasePathForTestResult(testResultId));
        if (Files.isDirectory(path)) {
            // The result directory also holds <millis>_test.log and <millis>_gaps.json, so the
            // listing filters on the force suffix.
            String[] names = path.toFile().list((dir, name) -> name.endsWith(FORCE_CSV_SUFFIX));
            return names == null ? new String[0] : names;
        } else {
            return new String[0];
        }
    }

    public String readCSVDataForTestResult(long testResultId, String fileName) {
        Path path = resolveWithin(Paths.get(getBasePathForTestResult(testResultId)), fileName);
        if (path.toFile().exists()) {
            try {
                return Files.readString(path);
            } catch (IOException e) {
                throw new RuntimeException("Failed to read file", e);
            }
        } else {
            return "";
        }
    }

    public String getPeaksFromResultFiles(long id) {
        String[] paths = this.listCSVFilesForTestResult(id);
        var results = Arrays.stream(paths)
                .map(path -> getPeakFromResultFile(id, path))
                .filter(Objects::nonNull)
                .collect(Collectors.toList());

        return String.join(",", results);
    }

    /**
     * Peak force of one CSV in kN, or null for a file too short to trust. A row whose two columns
     * are not both numeric is skipped, so no malformed line reaches the caller as an exception.
     *
     * <p>Lines split on {@code \R}: the file is written by command-deck's {@code LoadCellThread}
     * on the bench and may be read on a different OS, so both line endings must parse.
     */
    protected String getPeakFromResultFile(long id, String path) {
        Path file = Paths.get(path);
        String data = this.readCSVDataForTestResult(id, file.getFileName().toString());

        List<String> lines = Arrays.asList(data.split("\\R"));
        if (lines.size() < 100) {
            return null;
        }

        var peakKiloNewton = lines.stream().map(line -> line.split(","))
                .filter(cols -> cols.length == 2 && isTimestamp(cols[0]) && isNumber(cols[1]))
                .map(cols -> Double.parseDouble(cols[1]) / 1000)
                //remove values above 300, as the load cell can go maximal to 200kN (20 Tonnes)
                .filter(value -> value < 300)
                .max(Double::compareTo).orElse(0.0);

        return String.valueOf(peakKiloNewton);
    }

    /** A row is force data only if its first column is epoch millis; the age of the run is irrelevant. */
    private static boolean isTimestamp(String millisecondsString) {
        try {
            Long.parseLong(millisecondsString);
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /** A row carries force only if its second column parses; a malformed one is skipped, not thrown. */
    private static boolean isNumber(String forceString) {
        try {
            Double.parseDouble(forceString);
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /** A caller-supplied file name resolves inside its result directory or not at all. */
    private static Path resolveWithin(Path base, String name) {
        Path resolved = base.resolve(name).normalize();
        if (!resolved.startsWith(base.normalize())) {
            throw new SecurityException("File name escapes its directory: " + name);
        }
        return resolved;
    }

    private String getBasePathForTestResult(long testResultId) {
        String path = storageLocationService.getResultDataLocation().resolve(Long.toString(testResultId)).toString();
        log.debug("Base path for test result {}: {}", testResultId, path);
        return path;
    }
}
