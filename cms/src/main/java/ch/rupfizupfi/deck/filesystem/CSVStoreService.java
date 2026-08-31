package ch.rupfizupfi.deck.filesystem;

import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.logging.Logger;
import java.util.stream.Collectors;

@Service
public class CSVStoreService {
    private static final Logger log = Logger.getLogger(CSVStoreService.class.getName());

    private static final String FORCE_CSV_SUFFIX = "_force.csv";
    private static final String GAPS_SIDECAR_SUFFIX = "_gaps.json";

    protected long minTimeStamp = 0;
    protected final StorageLocationService storageLocationService;

    public CSVStoreService(StorageLocationService storageLocationService) {
        this.storageLocationService = storageLocationService;
    }

    public String generateFilePathForTestResult(long testResultId) {
        String filePath = Paths.get(getBasePathForTestResult(testResultId), System.currentTimeMillis() + FORCE_CSV_SUFFIX).toString();
        Paths.get(filePath).getParent().toFile().mkdirs();
        return filePath;
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

    public String[] listCSVFilesForTestResult(long testResultId) {
        Path path = Paths.get(getBasePathForTestResult(testResultId));
        if (path.toFile().exists()) {
            // The result directory is not CSV-only: TestLogger writes <millis>_test.log beside the
            // force files and a run that lost the sensor adds a <millis>_gaps.json sidecar. Both
            // reach getPeakFromResultFile() through the Excel export in api/rest/DownloadResults,
            // where any file of 100+ lines without an '@' is fed to an unguarded Double.parseDouble;
            // both are also offered in the frontend's result-file dropdown as if they were data.
            return path.toFile().list((dir, name) -> name.endsWith(FORCE_CSV_SUFFIX));
        } else {
            return new String[0];
        }
    }

    public String readCSVDataForTestResult(long testResultId, String fileName) {
        Path path = Paths.get(getBasePathForTestResult(testResultId), fileName);
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
        this.minTimeStamp = (System.currentTimeMillis() / 1000) - 60 * 60 * 24 * 4;

        String[] paths = this.listCSVFilesForTestResult(id);
        var results = Arrays.stream(paths)
                .map(path -> getPeakFromResultFile(id, path))
                .filter(Objects::nonNull)
                .collect(Collectors.toList());

        return String.join(",", results);
    }

    /**
     * Peak force of one CSV in kN, or null for a file too short or malformed to trust.
     *
     * <p>Lines split on {@code \R}: the file is written by command-deck's {@code LoadCellThread}
     * on the bench and may be read on a different OS, so both line endings must parse.
     */
    protected String getPeakFromResultFile(long id, String path) {
        Path file = Paths.get(path);
        String data = this.readCSVDataForTestResult(id, file.getFileName().toString());

        if (data.contains("@")) {
            return null;
        }

        List<String> lines = Arrays.asList(data.split("\\R"));
        if (lines.size() < 100) {
            return null;
        }

        var peake = lines.stream().map(line -> line.split(",")).filter(cols -> cols.length == 2 && isValidTimeStamp(cols[0]))
                .map(cols -> Double.parseDouble(cols[1]) / 1000)
                //remove values above 300, as the load cell can go maximal to 200kN (20 Tonnes)
                .filter(value -> value < 300)
                .max(Double::compareTo).orElse(0.0);

        return String.valueOf(peake);
    }

    protected boolean isValidTimeStamp(String millisecondsString) {
        try {
            long milliseconds = Long.parseLong(millisecondsString);
            return this.minTimeStamp < milliseconds / 1000;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private String getBasePathForTestResult(long testResultId) {
        String path = storageLocationService.getResultDataLocation().resolve(Long.toString(testResultId)).toString();
        log.info("Base path for test result: " + path);
        return path;
    }
}