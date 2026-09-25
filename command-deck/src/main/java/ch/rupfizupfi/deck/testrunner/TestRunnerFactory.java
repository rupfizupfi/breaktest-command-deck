package ch.rupfizupfi.deck.testrunner;

import ch.rupfizupfi.deck.api.services.SuckService;
import ch.rupfizupfi.deck.data.TestResult;
import ch.rupfizupfi.deck.data.TestResultRepository;
import ch.rupfizupfi.deck.device.DeviceService;
import ch.rupfizupfi.deck.device.HardwareModeInfo;
import ch.rupfizupfi.deck.device.loadcell.LoadCellDevice;
import ch.rupfizupfi.deck.filesystem.CSVStoreService;
import ch.rupfizupfi.deck.filesystem.StorageLocationService;
import ch.rupfizupfi.deck.testrunner.startup.check.AbstractCheck;
import ch.rupfizupfi.deck.testrunner.startup.check.FileSystemCheck;
import ch.rupfizupfi.deck.testrunner.startup.check.FrequencyInverterCheck;
import ch.rupfizupfi.deck.testrunner.startup.check.LoadCellCheck;
import org.springframework.context.ApplicationContext;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.lang.reflect.Constructor;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.IntSupplier;
import java.util.function.LongSupplier;
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
            // Class#getConstructors defines no order, so a second public constructor would silently
            // wire a different collaborator set into a motor-driving run.
            Constructor<?>[] constructors = testRunnerClass.getConstructors();
            if (constructors.length != 1) {
                throw new IllegalStateException(testRunnerClass.getName()
                        + " must declare exactly one public constructor, found " + constructors.length);
            }
            Constructor<T> constructor = (Constructor<T>) constructors[0];

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
                    try {
                        parameters[i] = applicationContext.getBean(parameterTypes[i]);
                    } catch (Exception e) {
                        throw new IllegalStateException("cannot resolve constructor parameter " + i
                                + " (" + parameterTypes[i].getName() + ") of " + testRunnerClass.getName(), e);
                    }
                }
            }

            return constructor.newInstance(parameters);
        } catch (IllegalStateException e) {
            // Already names the class and the parameter it stumbled on; wrapping would bury that.
            throw e;
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
                                               RecoveryGates gates, GapRecorder gapRecorder) {
        return new LoadCellThread(testContext, loadCellDevice, runFiles, getMotorSafetyController(),
                lossListener, gates, gapRecorder);
    }

    public SensorReconnector createReconnector(LoadCellDevice loadCellDevice, LoadCellThread loadCellThread,
                                               RecoveryGates gates) {
        return new SensorReconnector(loadCellDevice, loadCellThread, gates);
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
                                                           RecoveryGates gates, IntSupplier gapCount,
                                                           LongSupplier droppedSampleCount) {
        return new TestResultStatusPersister(testResultId,
                applicationContext.getBean(TestResultRepository.class),
                applicationContext.getBean(ObjectMapper.class), persistExecutor, gates, gapCount,
                droppedSampleCount);
    }

    public SuckJob createSuckJob(int durationSeconds) {
        return new SuckJob(durationSeconds, applicationContext.getBean(SuckService.class));
    }

    public TestLogger createLogger(TestResult testResult) {
        return new TestLogger(testResult, applicationContext.getBean(SimpMessagingTemplate.class), applicationContext.getBean(StorageLocationService.class));
    }

    public AbstractCheck[] getStartupChecks() {
        return new AbstractCheck[] {
            new FileSystemCheck(applicationContext.getBean(StorageLocationService.class)),
            new LoadCellCheck(applicationContext.getBean(DeviceService.class)),
            new FrequencyInverterCheck(applicationContext.getBean(DeviceService.class).getFrequencyInverter())
        };
    }
}
