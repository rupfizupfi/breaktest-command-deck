package ch.rupfizupfi.deck.testrunner;

import ch.rupfizupfi.deck.data.TestResult;
import ch.rupfizupfi.deck.filesystem.StorageLocationService;
import org.slf4j.Logger;
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
    private static final Logger logger = org.slf4j.LoggerFactory.getLogger(TestLogger.class);
    private final TestResult testResult;
    private final SimpMessagingTemplate template;
    private final Path logPath;
    private BufferedWriter writer;

    public TestLogger(TestResult testResult, SimpMessagingTemplate template, StorageLocationService storageLocationService) {
        this.testResult = testResult;
        this.template = template;
        this.logPath = storageLocationService.getResultDataLocation().resolve(Long.toString(this.testResult.getId()), System.currentTimeMillis() + "_test.log");
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
            logger.error("Failed to write to log file for test id:{}", testResult.getId(), e);
        }
    }

    public void end() {
        if (writer != null) {
            try {
                writer.close();
            } catch (IOException e) {
                logger.error("Failed to close log file for test id:{}", testResult.getId(), e);
            }
        }
    }
}