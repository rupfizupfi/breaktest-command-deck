package ch.rupfizupfi.deck.testrunner;

import ch.rupfizupfi.deck.data.TestResult;
import ch.rupfizupfi.usbmodbus.Cfw11;

import java.io.IOException;

public class TestRunnerThread {
    private final TestRunnerFactory testRunnerFactory;
    private volatile boolean running = false;
    private TestResult testResult;
    private AbstractTest test;
    private TestLogger testLogger;
    private Thread thread;

    public TestRunnerThread(TestRunnerFactory testRunnerFactory) {
        this.testRunnerFactory = testRunnerFactory;
    }

    protected void run() {
        try {
            // Sleep for 50ms to allow the client to set up the websocket connection
            Thread.sleep(50);
            testLogger.log("init test " + testResult.testParameter.type);
            test = switch (testResult.testParameter.type) {
                case "cyclic" -> testRunnerFactory.createTestRunner(CyclicTest.class, testResult, testLogger);
                case "timeCyclic" -> testRunnerFactory.createTestRunner(TimeCyclicTest.class, testResult, testLogger);
                case "destructive" -> testRunnerFactory.createTestRunner(DestructiveTest.class, testResult, testLogger);
                default -> test;
            };

            if (test != null) {
                test.runStartupChecks();
                test.setup();
                test.getContext().processSignals();
            }
        } catch (InterruptedException e) {
            testLogger.log("interrupt test " + testResult.testParameter.type);
        } catch (FinishTestException ignored) {
        } catch (Exception e) {
            testLogger.log("error: " + e.getClass() + ", " + e.getMessage());
            testLogger.log("error test " + testResult.testParameter.type);
            throw e;
        } finally {
            if (test != null) {
                try {
                    test.cleanup();
                    test.destroy();
                } catch (Exception e) {
                    testLogger.log("error: " + e.getClass() + ", " + e.getMessage());
                    retryShutdownOnException();
                }
            }
            this.test = null;
            this.running = false;
        }
    }

    public void startThread(TestResult testResult) {
        if (!running) {
            try {
                this.running = true;
                this.test = null;
                this.testResult = testResult;
                this.testLogger = testRunnerFactory.createLogger(testResult);
                this.testLogger.begin();
                this.thread = new Thread(this::run, "TestRunnerThread");
                this.thread.start();
            }
            catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    public void stopThread() {
        if (this.running) {
            this.test.getContext().sendSignal(0);
            try {
                this.thread.join(1000);
                if (this.thread.isAlive()) {
                    this.thread.interrupt();
                    this.thread.join();
                }
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            } finally {
                this.testLogger.end();
                this.testLogger = null;
                this.test = null;
            }
        }
    }

    public boolean isRunning() {
        return this.running;
    }

    public TestResult getTestResult() {
        return testResult;
    }

    protected void retryShutdownOnException() {
        try {
            test.destroy();
        } catch (Exception ignored) {
            test = null;
            System.gc();
        }

        var cfw11 = new Cfw11();
        cfw11.setGeneralEnable(false);
        cfw11.setSpeedReferenceValueAsRpm(0);
        cfw11.setStart(false);
        cfw11.getUsbComm().closeUSBComm();
    }
}
