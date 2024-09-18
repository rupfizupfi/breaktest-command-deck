package ch.rupfizupfi.deck.filesystem;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;

public class CSVStoreService {

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

    private String getBasePathForTestResult(long testResultId) {
        String userHome = System.getProperty("user.home");
        return Paths.get(userHome, "breaktester", Long.toString(testResultId)).toString();
    }
}