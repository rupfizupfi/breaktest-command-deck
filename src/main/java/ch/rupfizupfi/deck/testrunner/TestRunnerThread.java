package ch.rupfizupfi.deck.testrunner;

import ch.rupfizupfi.deck.data.TestResult;
import ch.rupfizupfi.dscusb.CellValueStream;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import java.io.BufferedWriter;


public class TestRunnerThread implements Runnable {
    private final SimpMessagingTemplate template;
    private volatile boolean running = false;
    private BufferedWriter writer;
    private CellValueStream stream;
    private TestResult testResult;

    public TestRunnerThread(SimpMessagingTemplate template) {
        this.template = template;
    }

    @Override
    public void run() {
        switch (testResult.testParameter.type)
        {
            case "cyclic":
                this.cyclicTest();
                break;
            case "destructive":
                this.destructiveTest();
                break;
        }
    }

    protected void cyclicTest(){

    }

    protected void destructiveTest(){
        TestContext testContext = new TestContext(testResult.getId(), testResult.testParameter.upperShutOffThreshold, testResult.testParameter.lowerShutOffThreshold);
        LoadCellThread loadCellThread = new LoadCellThread(template, testContext);
        new Thread(loadCellThread).start();

        // calculate from mm/min to rpm, 1 rpm = 0.375mm
//        cfw.setSpeed(testResult.testParameter.speed / 0.375);
//
//        testContext.onSignal([1,2], () -> {
//            cfw.stop();
//            loadCellThread.stopThread();
//            testResultRepository.save(test);
//        });
    }

    public void startThread(TestResult testResult) {
        if (!running) {
            this.testResult = testResult;
            running = true;
            new Thread(this).start();
        }
    }

    public void stopThread() {
        running = false;
    }
}
