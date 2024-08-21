package ch.rupfizupfi.deck.testrunner;

import ch.rupfizupfi.deck.data.TestResult;
import org.springframework.messaging.simp.SimpMessagingTemplate;

public class TestRunnerThread implements Runnable {
    private final SimpMessagingTemplate template;
    private volatile boolean running = false;
    private TestResult testResult;
    private AbstractTest test;

    public TestRunnerThread(SimpMessagingTemplate template) {
        this.template = template;
    }

    @Override
    public void run() {
        try {
            // Sleep for 50ms to allow the client to set up the websocket connection
            Thread.sleep(50);
            template.convertAndSend("/topic/logs", "init test " + testResult.testParameter.type);
            test = switch (testResult.testParameter.type) {
                case "cyclic" -> new CyclicTest(testResult, template);
                case "timeCyclic" -> new TimeCyclicTest(testResult, template);
                case "destructive" -> new DestructiveTest(testResult, template);
                default -> test;
            };

            if (test != null) {
                test.setup();
                test.getContext().processSignals();
            }
        } catch (InterruptedException e) {
            template.convertAndSend("/topic/logs", "interrupt test " + testResult.testParameter.type);
        } catch (Exception e) {
            template.convertAndSend("/topic/logs", e.getMessage());
            template.convertAndSend("/topic/logs", "error test " + testResult.testParameter.type);
        } finally {
            if (test != null) {
                test.cleanup();
            }
            this.test = null;
            this.running = false;
        }
    }

    public void startThread(TestResult testResult) {
        if (!running) {
            this.running = true;
            this.test = null;
            this.testResult = testResult;
            new Thread(this).start();
        }
    }

    public void stopThread() {
        if (this.running) {
            this.test.getContext().sendSignal(0);
        }
    }
}
