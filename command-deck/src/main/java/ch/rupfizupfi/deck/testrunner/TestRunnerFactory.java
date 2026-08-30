package ch.rupfizupfi.deck.testrunner;

import ch.rupfizupfi.deck.data.TestResult;
import ch.rupfizupfi.deck.data.TestResultRepository;
import ch.rupfizupfi.deck.device.DeviceService;
import ch.rupfizupfi.deck.device.HardwareModeInfo;
import ch.rupfizupfi.deck.device.loadcell.LoadCellDevice;
import ch.rupfizupfi.deck.filesystem.CSVStoreService;
import ch.rupfizupfi.deck.filesystem.StorageLocationService;
import ch.rupfizupfi.deck.testrunner.startup.check.AbstractCheck;
import ch.rupfizupfi.deck.testrunner.startup.check.FileSystemCheck;
import ch.rupfizupfi.deck.testrunner.startup.check.LoadCellCheck;
import org.springframework.context.ApplicationContext;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.lang.reflect.Constructor;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

@Service
public class TestRunnerFactory {
    private final ApplicationContext applicationContext;

    public TestRunnerFactory(ApplicationContext applicationContext) {
        this.applicationContext = applicationContext;
    }

    public TestRunnerThread createTestRunnerThread() {
        return new TestRunnerThread(this, getMotorSafetyController(),
                applicationContext.getBean(HardwareModeInfo.class));
    }

    public MotorSafetyController getMotorSafetyController() {
        return applicationContext.getBean(MotorSafetyController.class);
    }

    @SuppressWarnings("unchecked")
    public <T extends AbstractTest> T createTestRunner(Class<T> testRunnerClass, TestResult testResult, TestLogger testLogger) {
        try {
            Constructor<T> constructor;
            Constructor<?> firstConstructor = testRunnerClass.getConstructors()[0];
            if (firstConstructor.getDeclaringClass().equals(testRunnerClass)) {
                constructor = (Constructor<T>) firstConstructor;
            } else {
                throw new RuntimeException("Failed to found constructor for " + testRunnerClass.getName());
            }

            Class<?>[] parameterTypes = constructor.getParameterTypes();
            Object[] parameters = new Object[parameterTypes.length];

            for (int i = 0; i < parameterTypes.length; i++) {
                if (parameterTypes[i].equals(TestResult.class)) {
                    parameters[i] = testResult;
                } else if (parameterTypes[i].equals(TestRunnerFactory.class)) {
                    parameters[i] = this;
                } else if (parameterTypes[i].equals(TestLogger.class)) {
                    parameters[i] = testLogger;
                } else {
                    parameters[i] = applicationContext.getBean(parameterTypes[i]);
                }
            }

            return constructor.newInstance(parameters);
        } catch (Exception e) {
            throw new RuntimeException("Failed to create test runner instance", e);
        }
    }

    /**
     * The force CSV and its gap sidecar, named once per run. Both paths are fixed before the run
     * starts so a reconnect writes its gap record next to the trace it interrupts, rather than
     * deriving a second name from a later timestamp.
     */
    public CSVStoreService.TestRunFiles createRunFiles(long testResultId) {
        return applicationContext.getBean(CSVStoreService.class).generateRunFilesForTestResult(testResultId);
    }

    public GapRecorder createGapRecorder(long testResultId, CSVStoreService.TestRunFiles runFiles,
                                         RecoveryGates gates) {
        return new GapRecorder(runFiles, testResultId, gates, applicationContext.getBean(ObjectMapper.class));
    }

    public LoadCellThread createLoadCellThread(TestContext testContext, LoadCellDevice loadCellDevice,
                                               CSVStoreService.TestRunFiles runFiles,
                                               SensorLossListener lossListener,
                                               RecoveryProperties recovery, GapRecorder gapRecorder) {
        return new LoadCellThread(testContext, loadCellDevice, runFiles, getMotorSafetyController(),
                lossListener, recovery, gapRecorder);
    }

    public SensorReconnector createReconnector(LoadCellDevice loadCellDevice, LoadCellThread loadCellThread,
                                               RecoveryProperties recovery) {
        return new SensorReconnector(loadCellDevice, loadCellThread, recovery);
    }

    /**
     * The live bean. A run must snapshot it into {@link RecoveryGates} instead of holding this:
     * devtools reloads it mid-run in dev.
     */
    public RecoveryProperties recoveryProperties() {
        return applicationContext.getBean(RecoveryProperties.class);
    }

    public TestStateBroadcaster createStateBroadcaster(long testResultId, ScheduledExecutorService scheduler,
                                                       Supplier<TestStateMessage> snapshotSupplier) {
        return new TestStateBroadcaster(applicationContext.getBean(SimpMessagingTemplate.class), scheduler,
                testResultId, snapshotSupplier);
    }

    public TestResultStatusPersister createStatusPersister(long testResultId, Executor persistExecutor,
                                                           RecoveryGates gates, IntSupplier gapCount) {
        return new TestResultStatusPersister(testResultId,
                applicationContext.getBean(TestResultRepository.class),
                applicationContext.getBean(ObjectMapper.class), persistExecutor, gates, gapCount);
    }

    public TestLogger createLogger(TestResult testResult) {
        return new TestLogger(testResult, applicationContext.getBean(SimpMessagingTemplate.class), applicationContext.getBean(StorageLocationService.class));
    }

    public AbstractCheck[] getStartupChecks() {
        return new AbstractCheck[] {
            new FileSystemCheck(applicationContext.getBean(StorageLocationService.class)),
            new LoadCellCheck(applicationContext.getBean(DeviceService.class))
        };
    }
}
