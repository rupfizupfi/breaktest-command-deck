package ch.rupfizupfi.deck.testrunner;

import ch.rupfizupfi.deck.testrunner.measure.SensorReconnector;
import ch.rupfizupfi.deck.testrunner.measure.MeasurementLoop;
import ch.rupfizupfi.deck.api.services.SuckService;
import ch.rupfizupfi.deck.data.TestResult;
import ch.rupfizupfi.deck.data.SettingRepository;
import ch.rupfizupfi.deck.data.TestResultRepository;
import ch.rupfizupfi.deck.device.BenchPreparation;
import ch.rupfizupfi.deck.device.DeviceService;
import ch.rupfizupfi.deck.device.HardwareModeInfo;
import ch.rupfizupfi.deck.device.loadcell.LoadCellDevice;
import ch.rupfizupfi.deck.filesystem.CSVStoreService;
import ch.rupfizupfi.deck.filesystem.StorageLocationService;
import ch.rupfizupfi.deck.rig.RigLimits;
import ch.rupfizupfi.deck.testrunner.measure.ForceLimits;
import ch.rupfizupfi.deck.testrunner.measure.RunEventSink;
import ch.rupfizupfi.deck.testrunner.program.TestProgram;
import ch.rupfizupfi.deck.testrunner.program.TestProgramRegistry;
import ch.rupfizupfi.deck.testrunner.startup.check.AbstractCheck;
import ch.rupfizupfi.deck.testrunner.startup.check.FileSystemCheck;
import ch.rupfizupfi.deck.testrunner.startup.check.FrequencyInverterCheck;
import ch.rupfizupfi.deck.testrunner.startup.check.LoadCellCheck;
import org.springframework.context.ApplicationContext;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.util.Optional;
import java.util.concurrent.Executor;
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

    /** The bench's line for the test log; empty when nothing needs preparing (real hardware). */
    public Optional<String> prepareBench(TestResult testResult) {
        BenchPreparation preparation = applicationContext.getBeanProvider(BenchPreparation.class).getIfAvailable();
        return preparation == null ? Optional.empty() : Optional.of(preparation.prepare(testResult));
    }

    public MotorSafetyController getMotorSafetyController() {
        return applicationContext.getBean(MotorSafetyController.class);
    }

    /**
     * The run's program, from the registry keyed on the parameter's {@code TestType} (OQ-64 step 7),
     * given the run's own rig-limit snapshot.
     */
    public TestProgram createProgram(TestResult testResult, RigLimits rigLimits) {
        return applicationContext.getBean(TestProgramRegistry.class).create(testResult, rigLimits);
    }

    public DeviceService deviceService() {
        return applicationContext.getBean(DeviceService.class);
    }

    /**
     * The force CSV and its gap sidecar, named once before the run starts so the gap record always
     * sits next to the trace it interrupts.
     */
    public CSVStoreService.TestRunFiles createRunFiles(long testResultId) {
        return applicationContext.getBean(CSVStoreService.class).generateRunFilesForTestResult(testResultId);
    }

    public GapRecorder createGapRecorder(long testResultId, CSVStoreService.TestRunFiles runFiles,
                                         RecoveryGates gates) {
        return new GapRecorder(runFiles, testResultId, gates, applicationContext.getBean(ObjectMapper.class));
    }

    public MeasurementLoop createMeasurementLoop(ForceLimits limits, LoadCellDevice loadCellDevice,
                                               CSVStoreService.TestRunFiles runFiles, RunEventSink events,
                                               RecoveryGates gates, RigLimits rigLimits,
                                               GapRecorder gapRecorder) {
        return new MeasurementLoop(limits, loadCellDevice, runFiles, getMotorSafetyController()::safeStop,
                events, gates, rigLimits, gapRecorder);
    }

    public SensorReconnector createReconnector(LoadCellDevice loadCellDevice, MeasurementLoop measurementLoop,
                                               RecoveryGates gates) {
        return new SensorReconnector(loadCellDevice, measurementLoop, gates);
    }

    /**
     * The live bean. A run must snapshot it into {@link RecoveryGates} instead of holding this:
     * devtools reloads it mid-run in dev.
     */
    public RecoveryProperties recoveryProperties() {
        return applicationContext.getBean(RecoveryProperties.class);
    }

    /**
     * The rig limits as the settings stand now. A run takes this once and keeps it, as it does its
     * {@link RecoveryGates}.
     *
     * @throws IllegalStateException when a stored limit is no number or out of range
     */
    public RigLimits rigLimits() {
        return RigLimits.read(applicationContext.getBean(SettingRepository.class));
    }

    public TestStateBroadcaster createStateBroadcaster(long testResultId,
                                                       Supplier<TestStateMessage> snapshotSupplier) {
        return new TestStateBroadcaster(applicationContext.getBean(SimpMessagingTemplate.class),
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

    /**
     * The job's alarm goes to {@code /topic/logs} directly: it fires seconds after the run ended its
     * {@link TestLogger}, so the dashboard log pane shows it and no run-log file holds it.
     */
    public SuckJob createSuckJob(int durationSeconds) {
        SimpMessagingTemplate template = applicationContext.getBean(SimpMessagingTemplate.class);
        return new SuckJob(durationSeconds, applicationContext.getBean(SuckService.class),
                line -> template.convertAndSend("/topic/logs", line));
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
