package ch.rupfizupfi.deck.testrunner;

import ch.rupfizupfi.deck.data.TestResult;
import ch.rupfizupfi.deck.filesystem.StorageLocationService;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Specialized class for logging messages to the frontend and file
 */
public class TestLogger {
    private final TestResult testResult;
    private final SimpMessagingTemplate template;
    private final Path logPath;
    private BufferedWriter writer;

    public TestLogger(TestResult testResult, SimpMessagingTemplate template, StorageLocationService storageLocationService) {
        this.testResult = testResult;
        this.template = template;
        this.logPath = storageLocationService.getResultDataLocation().resolve(Long.toString(testResult.getId()), System.currentTimeMillis() + "_test.log");
    }

    public void begin() throws IOException {
        logPath.getParent().toFile().mkdirs();
        writer = Files.newBufferedWriter(logPath, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    public void log(String message) {
        template.convertAndSend("/topic/logs", message);
        try {
            if (writer != null) {
                writer.write(message);
                writer.newLine();
                writer.flush();
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public void end() {
        if (writer != null) {
            try {
                writer.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }
}