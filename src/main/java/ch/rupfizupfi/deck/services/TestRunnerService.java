package ch.rupfizupfi.deck.services;

import ch.rupfizupfi.deck.testrunner.TestRunnerThread;
import com.vaadin.hilla.BrowserCallable;
import jakarta.annotation.security.PermitAll;
import org.springframework.messaging.simp.SimpMessagingTemplate;

@BrowserCallable
@PermitAll
public class TestRunnerService {
    private final TestRunnerThread testRunnerThread;

    public TestRunnerService(SimpMessagingTemplate template) {
        this.testRunnerThread = new TestRunnerThread(template);
    }

    public void start(int testId) {
        testRunnerThread.startThread(testId);
    }

    public void stop() {
        testRunnerThread.stopThread();
    }
}