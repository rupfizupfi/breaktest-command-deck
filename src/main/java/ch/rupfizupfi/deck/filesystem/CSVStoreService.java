package ch.rupfizupfi.deck.filesystem;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

public class CSVStoreService {
    protected long minTimeStamp = 0;

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
        var results = Arrays.stream(paths).map(path -> getPeakFromResultFile(id, path))
                .filter(Objects::nonNull)
                .toArray(String[]::new);

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
                .map(cols -> Double.parseDouble(cols[1]))
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
        String userHome = System.getProperty("user.home");
        return Paths.get(userHome, "breaktester", Long.toString(testResultId)).toString();
    }
}