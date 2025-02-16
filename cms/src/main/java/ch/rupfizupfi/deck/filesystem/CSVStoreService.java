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

    protected long minTimeStamp = 0;
    protected final StorageLocationService storageLocationService;

    public CSVStoreService(StorageLocationService storageLocationService) {
        this.storageLocationService = storageLocationService;
    }

    public String generateFilePathForTestResult(long testResultId) {
        String filePath = Paths.get(getBasePathForTestResult(testResultId), System.currentTimeMillis() + "_force.csv").toString();
        Paths.get(filePath).getParent().toFile().mkdirs();
        return filePath;
    }

    public String[] listCSVFilesForTestResult(long testResultId) {
        Path path = Paths.get(getBasePathForTestResult(testResultId));
        if (path.toFile().exists()) {
            return path.toFile().list();
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

    protected String getPeakFromResultFile(long id, String path) {
        Path file = Paths.get(path);
        String data = this.readCSVDataForTestResult(id, file.getFileName().toString());

        if (data.contains("@")) {
            return null;
        }

        List<String> lines = Arrays.asList(data.split(System.lineSeparator()));
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