package ch.rupfizupfi.deck.api.services;

import ch.rupfizupfi.deck.data.TestResultRepository;
import ch.rupfizupfi.deck.device.DeviceService;
import ch.rupfizupfi.deck.testrunner.TestRunnerThread;
import com.vaadin.hilla.BrowserCallable;
import jakarta.annotation.security.PermitAll;
import org.springframework.messaging.simp.SimpMessagingTemplate;

@BrowserCallable
@PermitAll
public class TestRunnerService {
    private final TestRunnerThread testRunnerThread;
    private final TestResultRepository testResultRepository;

    public TestRunnerService(TestResultRepository testResultRepository, SimpMessagingTemplate template, DeviceService deviceService) {
        this.testResultRepository = testResultRepository;
        this.testRunnerThread = new TestRunnerThread(template, deviceService);
    }

    public void start(int testId) {
        testRunnerThread.startThread(testResultRepository.findById((long) testId).orElseThrow(() -> new RuntimeException("Test not found")));
    }

    public void stop() {
        testRunnerThread.stopThread();
    }
}