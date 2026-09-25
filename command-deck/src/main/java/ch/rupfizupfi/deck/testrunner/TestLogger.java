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
    private volatile BufferedWriter writer;

    public TestLogger(TestResult testResult, SimpMessagingTemplate template, StorageLocationService storageLocationService) {
        this.testResult = testResult;
        this.template = template;
        this.logPath = storageLocationService.getResultDataLocation().resolve(Long.toString(this.testResult.getId()), System.currentTimeMillis() + "_test.log");
    }

    public void begin() throws IOException {
        logPath.getParent().toFile().mkdirs();
        writer = Files.newBufferedWriter(logPath, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    /**
     * Absorbs every broadcast and file-write failure, including a concurrent {@link #end}:
     * teardown steps log through here.
     */
    public void log(String message) {
        try {
            template.convertAndSend("/topic/logs", message);
        } catch (Exception e) {
            logger.warn("Failed to broadcast log message {} for test id:{}", message, testResult.getId(), e);
        }
        BufferedWriter w = writer;
        try {
            if (w != null) {
                w.write(message);
                w.newLine();
                w.flush();
            }
        } catch (Exception e) {
            logger.error("Failed to write to log file for test id:{}", testResult.getId(), e);
        }
    }

    /**
     * Idempotent: the run's own teardown and an operator Stop both end the same logger.
     * A concurrent {@link #log} either writes or is a no-op.
     */
    public void end() {
        // One read of the field, claimed before the close: concurrent ends then close the same
        // descriptor at most twice, which BufferedWriter ignores.
        BufferedWriter w = writer;
        writer = null;
        if (w == null) {
            return;
        }

        try {
            w.close();
        } catch (IOException e) {
            logger.error("Failed to close log file for test id:{}", testResult.getId(), e);
        }
    }
}