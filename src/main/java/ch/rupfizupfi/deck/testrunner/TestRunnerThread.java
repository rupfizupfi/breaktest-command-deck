package ch.rupfizupfi.deck.testrunner;

import ch.rupfizupfi.deck.data.TestResult;
import ch.rupfizupfi.deck.testrunner.cyclic.CyclicTestContext;
import ch.rupfizupfi.usbmodbus.Cfw11;
//import ch.rupfizupfi.usbmodbus.Cfw11Controller;
//import ch.rupfizupfi.usbmodbus.commandbus.CommandChain;
import org.springframework.messaging.simp.SimpMessagingTemplate;


public class TestRunnerThread implements Runnable {
    private final SimpMessagingTemplate template;
    private volatile boolean running = false;
    private TestResult testResult;
    private TestContext testContext;

    public TestRunnerThread(SimpMessagingTemplate template) {
        this.template = template;
    }

    @Override
    public void run() {
        try {
            Thread.sleep(50);
            template.convertAndSend("/topic/logs", "init test " + testResult.testParameter.type);
            switch (testResult.testParameter.type) {
                case "cyclic":
                    this.cyclicTest();
                    break;
                case "destructive":
                    this.destructiveTest();
                    break;
            }
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    protected void cyclicTest() {
        testContext = new CyclicTestContext(
                testResult.getId(),
                testResult.testParameter.upperTurnForce * 1000,
                testResult.testParameter.lowerTurnForce * 1000,
                testResult.testParameter.cycleCount
        );
        running = true;
        double targetLowerLimit = testContext.getLowerLimit();
        double targetUpperLimit = testContext.getUpperLimit();

        LoadCellThread loadCellThread = new LoadCellThread(template, testContext);
        loadCellThread.setRunning(true);
        new Thread(loadCellThread).start();

        Cfw11 cfw11 = new Cfw11();
//        CommandChain commandChain = new CommandChain(1);
//        Cfw11Controller controller = new Cfw11Controller(cfw11, commandChain);
//        controller.start();

        template.convertAndSend("/topic/logs", "upperShutOffThreshold " + testContext.getUpperLimit() + " Newton");
        template.convertAndSend("/topic/logs", "lowerShutOffThreshold " + testContext.getLowerLimit() + " Newton");
        CyclicTestContext cyclicTestContext = (CyclicTestContext) testContext;
        template.convertAndSend("/topic/logs", "CycleCount " + cyclicTestContext.getCycleCount());
        template.convertAndSend("/topic/logs", "cyclic test start");


        cfw11.seSpeedValueAsRpm((int) Math.round(testResult.testParameter.speed / 0.375));
        cfw11.setDirection(false);
        cfw11.setGeneralEnable(true);
        cfw11.setStart(true);

//        controller.setSpeedValueAsRpm((int) Math.round(testResult.testParameter.speed / 0.375));
//        controller.setStart(true);
        template.convertAndSend("/topic/logs", "controller start cfw11");

        testContext.addSignalListener(new SignalListener() {
            @Override
            public void handleSignal(int signal) {
                switch (signal) {
                    case 0:
                        cfw11.setStart(false);
                        loadCellThread.setRunning(false);
                        template.convertAndSend("/topic/logs", "operation stopped 2");
                        running = false;
                        break;
                    case 1: //upper limit triggered
                        if (!cfw11.getDirection()) {
                            double diff = targetLowerLimit - loadCellThread.getMinValue();
                            if (diff != 0.0) {
                                template.convertAndSend("/topic/logs", "Current min value " + loadCellThread.getMinValue());
                                testContext.setLowerLimit(testContext.getLowerLimit() + diff);
                                template.convertAndSend("/topic/logs", "New lower limit " + testContext.getLowerLimit());
                            }

                            template.convertAndSend("/topic/logs", "change direction to forward");
                            template.convertAndSend("/topic/logs", "CycleCount " + cyclicTestContext.getCycleCount());
                            cfw11.setDirection(true);
                            loadCellThread.setMinValue((float)targetUpperLimit);
                        }
                        break;
                    case 2:
                        if (cfw11.getDirection()) {
                            double diff = targetUpperLimit - loadCellThread.getMaxValue();
                            if (diff != 0.0) {
                                template.convertAndSend("/topic/logs", "Current max value " + loadCellThread.getMaxValue());
                                testContext.setUpperLimit(testContext.getUpperLimit() + diff);
                                template.convertAndSend("/topic/logs", "New upper limit " + testContext.getUpperLimit());
                            }

                            template.convertAndSend("/topic/logs", "change direction to backword");
                            template.convertAndSend("/topic/logs", "CycleCount " + cyclicTestContext.getCycleCount());
                            cfw11.setDirection(false);
                            loadCellThread.setMaxValue((float)targetLowerLimit);
                            CyclicTestContext cyclicTestContext = (CyclicTestContext) testContext;
                            cyclicTestContext.decrementCycleCount();
                        }
                        break;

                }
            }
        });
    }

    protected void destructiveTest() {
        testContext = new TestContext(testResult.getId(), testResult.testParameter.upperShutOffThreshold * 1000, testResult.testParameter.lowerShutOffThreshold * 1000);
        running = true;
        LoadCellThread loadCellThread = new LoadCellThread(template, testContext);
        loadCellThread.setRunning(true);
        new Thread(loadCellThread).start();

        Cfw11 cfw11 = new Cfw11();
//        CommandChain commandChain = new CommandChain(1);
//        Cfw11Controller controller = new Cfw11Controller(cfw11, commandChain);
//        controller.start();

        template.convertAndSend("/topic/logs", "upperShutOffThreshold " + testContext.getUpperLimit() + " Newton");
        template.convertAndSend("/topic/logs", "lowerShutOffThreshold " + testContext.getLowerLimit() + " Newton");
        template.convertAndSend("/topic/logs", "controller start");

        cfw11.seSpeedValueAsRpm((int) Math.round(testResult.testParameter.speed / 0.375));
        cfw11.setDirection(false);
        cfw11.setGeneralEnable(true);
        cfw11.setStart(true);

//        controller.setSpeedValueAsRpm((int) Math.round(testResult.testParameter.speed / 0.375));
//        controller.setStart(true);
        template.convertAndSend("/topic/logs", "controller start cfw11");

        testContext.addSignalListener(new SignalListener() {
            @Override
            public void handleSignal(int signal) {
                //currently we stop at every signal (currently only lower and upper limits)
//                controller.setStart(false);
                cfw11.setStart(false);
                loadCellThread.setRunning(false);
                template.convertAndSend("/topic/logs", "operation stopped");
                running = false;
            }
        });
    }

    public void startThread(TestResult testResult) {
        if (!running) {
            this.testResult = testResult;
            new Thread(this).start();
        }
    }

    public void stopThread() {
        if (this.running) {
            this.testContext.sendSignal(0);
        }
    }
}
