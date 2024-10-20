package ch.rupfizupfi.deck.testrunner;

import ch.rupfizupfi.deck.data.TestResult;
import ch.rupfizupfi.deck.device.DeviceService;
import ch.rupfizupfi.usbmodbus.Cfw11;
import org.springframework.messaging.simp.SimpMessagingTemplate;

public class TestRunnerThread implements Runnable {
    private final SimpMessagingTemplate template;
    private final DeviceService deviceService;
    private volatile boolean running = false;
    private TestResult testResult;
    private AbstractTest test;

    public TestRunnerThread(SimpMessagingTemplate template, DeviceService deviceService) {
        this.template = template;
        this.deviceService = deviceService;
    }

    @Override
    public void run() {
        try {
            // Sleep for 50ms to allow the client to set up the websocket connection
            Thread.sleep(50);
            template.convertAndSend("/topic/logs", "init test " + testResult.testParameter.type);
            test = switch (testResult.testParameter.type) {
                case "cyclic" -> new CyclicTest(testResult, template, deviceService);
                case "timeCyclic" -> new TimeCyclicTest(testResult, template, deviceService);
                case "destructive" -> new DestructiveTest(testResult, template, deviceService);
                default -> test;
            };

            if (test != null) {
                test.setup();
                test.getContext().processSignals();
            }
        } catch (InterruptedException e) {
            template.convertAndSend("/topic/logs", "interrupt test " + testResult.testParameter.type);
        } catch (FinishTestException ignored) {
        } catch (Exception e) {
            template.convertAndSend("/topic/logs", "error: " + e.getClass() + ", " + e.getMessage());
            template.convertAndSend("/topic/logs", "error test " + testResult.testParameter.type);
        } finally {
            if (test != null) {
                try {
                    test.cleanup();
                    test.destroy();
                } catch (Exception e) {
                    template.convertAndSend("/topic/logs", "error: " + e.getClass() + ", " + e.getMessage());
                    retryShutdownOnException();
                }
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

    protected void retryShutdownOnException() {
        try {
            test.destroy();
        }
        catch (Exception ignored) {
            test = null;
            System.gc();
        }

        var cfw11 = new Cfw11();
        cfw11.setGeneralEnable(false);
        cfw11.setSpeedValueAsRpm(0);
        cfw11.setStart(false);
        cfw11.getUsbComm().closeUSBComm();
    }
}
