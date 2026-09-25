package ch.rupfizupfi.deck.testrunner;

import ch.rupfizupfi.deck.data.TestResult;
import ch.rupfizupfi.deck.filesystem.StorageLocationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.messaging.MessageDeliveryException;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TestLoggerTest {

    @TempDir
    Path resultData;

    private TestResult testResult;
    private StorageLocationService storageLocationService;
    private TestLogger testLogger;

    @BeforeEach
    void createLogger() {
        testResult = new TestResult();
        testResult.setId(42L);

        storageLocationService = mock(StorageLocationService.class);
        when(storageLocationService.getResultDataLocation()).thenReturn(resultData);

        testLogger = new TestLogger(testResult, mock(SimpMessagingTemplate.class), storageLocationService);
    }

    @Test
    void writesTheLineAndSurvivesEveryTeardownOrder() throws IOException {
        testLogger.begin();
        testLogger.log("init test destructive");

        assertThat(logLines()).contains("init test destructive");

        // The run's own teardown and an operator Stop both end the logger, so the second end() and
        // any late log() have to be no-ops rather than exceptions.
        assertThatCode(() -> {
            testLogger.end();
            testLogger.end();
            testLogger.log("after the end");
        }).doesNotThrowAnyException();

        assertThat(logLines()).containsExactly("init test destructive");
    }

    @Test
    @Timeout(30)
    void aLogRacingATeardownNeverThrows() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        List<Throwable> escaped = Collections.synchronizedList(new ArrayList<>());
        try {
            for (int i = 0; i < 300; i++) {
                testLogger.begin();
                CyclicBarrier start = new CyclicBarrier(2);
                Future<?> ender = pool.submit(() -> race(start, escaped, testLogger::end));
                Future<?> writer = pool.submit(() -> race(start, escaped, () -> testLogger.log("racing line")));
                ender.get();
                writer.get();
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(escaped).isEmpty();
    }

    @Test
    @Timeout(30)
    void twoTeardownsRacingEachOtherNeverThrow() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        List<Throwable> escaped = Collections.synchronizedList(new ArrayList<>());
        try {
            for (int i = 0; i < 300; i++) {
                testLogger.begin();
                CyclicBarrier start = new CyclicBarrier(2);
                Future<?> first = pool.submit(() -> race(start, escaped, testLogger::end));
                Future<?> second = pool.submit(() -> race(start, escaped, testLogger::end));
                first.get();
                second.get();
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(escaped).isEmpty();
    }

    @Test
    void aBrokerThatRejectsTheBroadcastStillLeavesTheLineOnDisk() throws IOException {
        SimpMessagingTemplate rejecting = mock(SimpMessagingTemplate.class);
        doThrow(new MessageDeliveryException("no broker"))
                .when(rejecting).convertAndSend(anyString(), any(Object.class));
        TestLogger broadcastingLogger = new TestLogger(testResult, rejecting, storageLocationService);
        broadcastingLogger.begin();

        assertThatCode(() -> broadcastingLogger.log("stopping the drive")).doesNotThrowAnyException();
        broadcastingLogger.end();

        assertThat(logLines()).contains("stopping the drive");
    }

    private static void race(CyclicBarrier start, List<Throwable> escaped, Runnable action) {
        try {
            start.await();
            action.run();
        } catch (Throwable t) {
            escaped.add(t);
        }
    }

    private List<String> logLines() throws IOException {
        try (var files = Files.list(resultData.resolve("42"))) {
            Path log = files.findFirst().orElseThrow();
            return Files.readAllLines(log);
        }
    }
}
