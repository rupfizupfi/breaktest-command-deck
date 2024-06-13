package ch.rupfizupfi.deck.services;

import ch.rupfizupfi.deck.data.TestResultRepository;
import ch.rupfizupfi.deck.testrunner.TestRunnerThread;
import com.vaadin.hilla.BrowserCallable;
import jakarta.annotation.security.PermitAll;
import org.springframework.messaging.simp.SimpMessagingTemplate;

@BrowserCallable
@PermitAll
public class TestRunnerService {
    private final TestRunnerThread testRunnerThread;
    private final TestResultRepository testResultRepository;

    public TestRunnerService(SimpMessagingTemplate template, TestResultRepository testResultRepository) {
        this.testRunnerThread = new TestRunnerThread(template);
        this.testResultRepository = testResultRepository;
    }

    public void start(int testId) {
        testRunnerThread.startThread(testResultRepository.findById((long) testId).orElseThrow(() -> new RuntimeException("Test not found")));
    }

    public void stop() {
        testRunnerThread.stopThread();
    }
}