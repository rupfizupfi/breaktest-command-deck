package ch.rupfizupfi.deck.testrunner;

import ch.rupfizupfi.deck.data.RunStatus;
import ch.rupfizupfi.deck.data.TestResult;
import ch.rupfizupfi.deck.data.TestResultRepository;
import ch.rupfizupfi.deck.device.DeviceService;
import ch.rupfizupfi.deck.device.HardwareModeInfo;
import ch.rupfizupfi.deck.device.api.ControlSource;
import ch.rupfizupfi.deck.device.api.Drive;
import ch.rupfizupfi.deck.device.api.DriveDeadlineException;
import ch.rupfizupfi.deck.device.api.DriveGating;
import ch.rupfizupfi.deck.device.api.DriveLane;
import ch.rupfizupfi.deck.device.api.DriveProvider;
import ch.rupfizupfi.deck.device.frequencyinverter.FrequencyInverterDevice;
import ch.rupfizupfi.deck.device.simulated.SimulatedBench;
import ch.rupfizupfi.deck.device.simulated.SimulatedBenchProperties;
import ch.rupfizupfi.deck.device.simulated.SimulatedDriveProvider;
import ch.rupfizupfi.deck.device.simulated.SimulatedFaultSwitches;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullSource;
import org.mockito.InOrder;
import org.springframework.beans.factory.ObjectProvider;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The synchronous sweep, plus the boot de-energize. The de-energize runs on a detached daemon thread
 * ("startup-drive-deenergize") because a drive open can wedge forever, so the tests that cross into
 * it synchronize on {@link BootDeEnergizeGate}, which that thread opens last, with a bounded await.
 * The device is a real {@link FrequencyInverterDevice}, so its reference count and monitor are
 * the production ones.
 */
class StartupRecoveryRunnerTest {

    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {
    };

    private final TestResultRepository repository = mock(TestResultRepository.class);
    private final DriveProvider driveProvider = mock(DriveProvider.class);
    private final Drive drive = mock(Drive.class);
    private final FrequencyInverterDevice device = new FrequencyInverterDevice(driveProvider);
    private final ObjectMapper mapper = new ObjectMapper();

    StartupRecoveryRunnerTest() {
        when(driveProvider.open()).thenReturn(drive);
    }

    @AfterEach
    void leaveTheGateOpen() {
        // Process-wide: a test that left it closed would hold back the starts of TestRunnerThreadTest.
        BootDeEnergizeGate.resetForTests();
    }

    private StartupRecoveryRunner runner(String hardwareMode) {
        return runner(hardwareMode, ControlSource.UNKNOWN);
    }

    private StartupRecoveryRunner runner(String hardwareMode, ControlSource place) {
        return new StartupRecoveryRunner(repository, mapper, new HardwareModeInfo(hardwareMode),
                () -> device, true, d -> place);
    }

    private static void awaitDeEnergize() throws InterruptedException {
        assertThat(BootDeEnergizeGate.awaitReady(5000))
                .as("the startup-drive-deenergize thread finished")
                .isTrue();
    }

    private static TestResult orphan(long id, RunStatus status, String interruptionLog) {
        TestResult result = new TestResult();
        result.setId(id);
        result.runStatus = status;
        result.interruptionLog = interruptionLog;
        return result;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> eventsOf(Map<String, Object> log) {
        return (List<Map<String, Object>>) log.get("events");
    }

    @ParameterizedTest
    @EnumSource(RunStatus.class)
    void sweepQueriesAStatusExactlyWhenItIsNonTerminal(RunStatus status) {
        List<Collection<RunStatus>> queries = new ArrayList<>();
        when(repository.findByRunStatusIn(anyCollection())).thenAnswer(invocation -> {
            queries.add(invocation.getArgument(0));
            return List.of();
        });

        runner("simulated").onApplicationReady(null);

        // Exhaustive switch, no default: a new RunStatus fails compilation here until someone
        // decides whether the boot sweep must pick it up.
        boolean expected = switch (status) {
            case IN_PROGRESS, INTERRUPTED -> true;
            case COMPLETED, COMPLETED_WITH_GAPS, ABORTED, FAULT -> false;
        };
        assertThat(queries).hasSize(1);
        assertThat(queries.getFirst().contains(status)).isEqualTo(expected);
    }

    @Test
    void everySweptRowIsSavedAsAborted() {
        TestResult inProgress = orphan(1L, RunStatus.IN_PROGRESS, null);
        TestResult interrupted = orphan(2L, RunStatus.INTERRUPTED, null);
        when(repository.findByRunStatusIn(anyCollection()))
                .thenReturn(List.of(inProgress, interrupted));

        runner("simulated").onApplicationReady(null);

        verify(repository).save(inProgress);
        verify(repository).save(interrupted);
        assertThat(inProgress.runStatus).isEqualTo(RunStatus.ABORTED);
        assertThat(interrupted.runStatus).isEqualTo(RunStatus.ABORTED);
    }

    @Test
    void oneUnsaveableRowDoesNotStrandTheOthers() {
        TestResult first = orphan(1L, RunStatus.IN_PROGRESS, null);
        TestResult failing = orphan(2L, RunStatus.IN_PROGRESS, null);
        TestResult third = orphan(3L, RunStatus.INTERRUPTED, null);
        when(repository.findByRunStatusIn(anyCollection()))
                .thenReturn(List.of(first, failing, third));
        when(repository.save(failing)).thenThrow(new RuntimeException("row is gone"));

        assertThatCode(() -> runner("simulated").onApplicationReady(null))
                .doesNotThrowAnyException();

        verify(repository).save(first);
        verify(repository).save(third);
        assertThat(first.runStatus).isEqualTo(RunStatus.ABORTED);
        assertThat(third.runStatus).isEqualTo(RunStatus.ABORTED);
    }

    @Test
    void markerOnARowWithNoLogIsASingleEventDocument() {
        TestResult row = orphan(1L, RunStatus.IN_PROGRESS, null);
        when(repository.findByRunStatusIn(anyCollection())).thenReturn(List.of(row));

        runner("simulated").onApplicationReady(null);

        Map<String, Object> log = mapper.readValue(row.interruptionLog, MAP);
        assertThat(log).doesNotContainKey("previousLog");
        List<Map<String, Object>> events = eventsOf(log);
        assertThat(events).hasSize(1);
        Map<String, Object> marker = events.getFirst();
        assertThat(marker.get("event")).isEqualTo("orphan-abort");
        assertThat(marker.get("previousStatus")).isEqualTo("IN_PROGRESS");
        assertThat(marker.get("at")).isInstanceOf(Number.class);
        assertThat((String) marker.get("reason")).isNotBlank();
    }

    @Test
    void markerLeavesARealPersisterDocumentIntactAndLandsInEvents() {
        // A crashed run's TestResultStatusPersister LogDocument. The marker goes into `events`,
        // never into the state machine's own `transitions`.
        String existing = "{\"schema\":1,"
                + "\"gates\":{\"reconnectWindowMillis\":10000,\"backoffMillis\":[1000],"
                + "\"safeHoldTimeoutMillis\":60000,\"maxHoldForResumeMillis\":30000,"
                + "\"plausibilityGateMillis\":100,\"driftFraction\":0.02,\"maxLossesPerRun\":2,"
                + "\"minEnvelopeNewton\":1000.0,\"resumeEnabled\":true},"
                + "\"transitions\":["
                + "{\"seq\":1,\"from\":\"IDLE\",\"to\":\"STARTING\",\"at\":1700000000000,"
                + "\"reason\":null,\"actor\":\"operator\"},"
                + "{\"seq\":2,\"from\":\"RUNNING\",\"to\":\"SENSOR_LOST\",\"at\":1700000001000,"
                + "\"reason\":\"stream ended\",\"actor\":\"loadcell-thread\"}],"
                + "\"gapCount\":1}";
        TestResult row = orphan(1L, RunStatus.INTERRUPTED, existing);
        when(repository.findByRunStatusIn(anyCollection())).thenReturn(List.of(row));

        runner("simulated").onApplicationReady(null);

        // The column must stay ONE parseable JSON document - readValue of the whole string
        // proves there is no second object concatenated onto the first.
        Map<String, Object> log = mapper.readValue(row.interruptionLog, MAP);
        Map<String, Object> original = mapper.readValue(existing, MAP);
        assertThat(log.get("schema")).isEqualTo(original.get("schema"));
        assertThat(log.get("gates")).isEqualTo(original.get("gates"));
        assertThat(log.get("transitions")).isEqualTo(original.get("transitions"));
        assertThat(log.get("gapCount")).isEqualTo(original.get("gapCount"));
        assertThat(log).doesNotContainKey("previousLog");
        List<Map<String, Object>> events = eventsOf(log);
        assertThat(events).hasSize(1);
        assertThat(events.getFirst().get("event")).isEqualTo("orphan-abort");
        assertThat(events.getFirst().get("previousStatus")).isEqualTo("INTERRUPTED");
    }

    @Test
    void aSecondSweepAppendsToTheEventsArrayInsteadOfReplacingIt() {
        // The sweep is the only producer of `events`, so merging into an existing array is
        // exercised by sweeping the same row twice.
        TestResult row = orphan(1L, RunStatus.IN_PROGRESS, null);
        when(repository.findByRunStatusIn(anyCollection())).thenReturn(List.of(row));
        runner("simulated").onApplicationReady(null);

        row.runStatus = RunStatus.INTERRUPTED;
        runner("simulated").onApplicationReady(null);

        Map<String, Object> log = mapper.readValue(row.interruptionLog, MAP);
        assertThat(log).doesNotContainKey("previousLog");
        List<Map<String, Object>> events = eventsOf(log);
        assertThat(events).hasSize(2);
        assertThat(events.getFirst().get("event")).isEqualTo("orphan-abort");
        assertThat(events.getFirst().get("previousStatus")).isEqualTo("IN_PROGRESS");
        assertThat(events.getLast().get("event")).isEqualTo("orphan-abort");
        assertThat(events.getLast().get("previousStatus")).isEqualTo("INTERRUPTED");
    }

    @Test
    void nonJsonLogIsPreservedVerbatimNeverDiscarded() {
        String garbage = "log written by an unknown earlier version {{{";
        TestResult row = orphan(1L, RunStatus.IN_PROGRESS, garbage);
        when(repository.findByRunStatusIn(anyCollection())).thenReturn(List.of(row));

        runner("simulated").onApplicationReady(null);

        Map<String, Object> log = mapper.readValue(row.interruptionLog, MAP);
        assertThat(log.get("previousLog")).isEqualTo(garbage);
        List<Map<String, Object>> events = eventsOf(log);
        assertThat(events).hasSize(1);
        assertThat(events.getFirst().get("event")).isEqualTo("orphan-abort");
    }

    @Test
    void zeroOrphansStillDeEnergizeARealBench() throws Exception {
        when(repository.findByRunStatusIn(anyCollection())).thenReturn(List.of());

        runner("real").onApplicationReady(null);
        awaitDeEnergize();

        verify(repository, never()).save(any());
        verify(drive).stop();
    }

    @Test
    void simulatedModeNeverTouchesTheDrive() {
        when(repository.findByRunStatusIn(anyCollection()))
                .thenReturn(List.of(orphan(1L, RunStatus.IN_PROGRESS, null)));

        runner("simulated").onApplicationReady(null);

        assertThat(BootDeEnergizeGate.isPending()).isFalse();
        verifyNoInteractions(driveProvider);
    }

    @Test
    void theDisablingPropertyKeepsARealBootOffTheHardware() {
        // What DriverPluginBootTest relies on to boot real mode without touching a drive.
        when(repository.findByRunStatusIn(anyCollection())).thenReturn(List.of());
        var runner = new StartupRecoveryRunner(repository, mapper, new HardwareModeInfo("real"),
                () -> device, false, d -> ControlSource.REMOTE);

        assertThat(BootDeEnergizeGate.isPending()).isFalse();
        runner.onApplicationReady(null);

        assertThat(BootDeEnergizeGate.isPending()).isFalse();
        verifyNoInteractions(driveProvider);
    }

    @Test
    void missingDeviceWarnsAndOpensTheGateInsteadOfThrowing() {
        TestResult row = orphan(1L, RunStatus.IN_PROGRESS, null);
        when(repository.findByRunStatusIn(anyCollection())).thenReturn(List.of(row));
        var runner = new StartupRecoveryRunner(repository, mapper, new HardwareModeInfo("real"),
                () -> null, true, d -> ControlSource.REMOTE);

        assertThatCode(() -> runner.onApplicationReady(null)).doesNotThrowAnyException();

        // The sweep is the part that must always happen, and it already has.
        assertThat(row.runStatus).isEqualTo(RunStatus.ABORTED);
        assertThat(BootDeEnergizeGate.isPending()).isFalse();
    }

    @Test
    void failingQueryStillDeEnergizes() throws Exception {
        when(repository.findByRunStatusIn(anyCollection()))
                .thenThrow(new RuntimeException("database not reachable"));

        assertThatCode(() -> runner("real").onApplicationReady(null))
                .doesNotThrowAnyException();
        awaitDeEnergize();

        verify(repository, never()).save(any());
        verify(drive).stop();
    }

    @Test
    void deEnergizeIsOneStopCallThenReleasesTheHandle() throws Exception {
        when(repository.findByRunStatusIn(anyCollection()))
                .thenReturn(List.of(orphan(1L, RunStatus.IN_PROGRESS, null)));

        runner("real", ControlSource.REMOTE).onApplicationReady(null);
        awaitDeEnergize();

        // Drive#stop(), as the safe stop sends it: start and general enable cleared and the speed
        // reference zero in one telegram on a gated driver, so the motor coasts and never reverses.
        InOrder order = inOrder(drive);
        order.verify(drive).stop();
        order.verify(drive).close();
        verify(drive, never()).setGeneralEnable(anyBoolean());
        verify(drive, never()).setSpeedReferenceValueAsRpm(anyInt());
        verify(drive, never()).setStart(anyBoolean());
        assertThat(device.isDriveHandleOpen()).isFalse();
    }

    /**
     * BL-121: a plugin built before device-api 1.5.0 answers CALLER_SERIALIZES and has no stop() of
     * its own. The deck refuses to energize through it, but the boot still de-energizes it with the
     * contract's two-call fallback stop.
     */
    @Test
    void anUngatedPluginIsStillDeEnergizedAtBoot() throws Exception {
        when(repository.findByRunStatusIn(anyCollection())).thenReturn(List.of());
        when(drive.gating()).thenCallRealMethod();
        doCallRealMethod().when(drive).stop();
        assertThat(drive.gating()).isEqualTo(DriveGating.CALLER_SERIALIZES);

        runner("real", ControlSource.REMOTE).onApplicationReady(null);
        awaitDeEnergize();

        InOrder order = inOrder(drive);
        order.verify(drive).setControlParameters(false, false, null, null, null);
        order.verify(drive).setSpeedReferenceValueAsRpm(0);
        order.verify(drive).close();
    }

    @Test
    void aDriveUnderLocalControlIsLeftAlone() throws Exception {
        // An operator may be jogging at the keypad: not one write may reach the drive.
        when(repository.findByRunStatusIn(anyCollection())).thenReturn(List.of());

        runner("real", ControlSource.LOCAL).onApplicationReady(null);
        awaitDeEnergize();

        verify(drive, never()).stop();
        verify(drive, never()).setGeneralEnable(anyBoolean());
        verify(drive, never()).setSpeedReferenceValueAsRpm(anyInt());
        verify(drive, never()).setStart(anyBoolean());
        verify(drive, never()).setControlParameters(any(), any(), any(), any(), any());
        assertThat(device.isDriveHandleOpen()).isFalse();
    }

    /** The Spring constructor, so the probe is the production one: {@link Drive#readControlSource}. */
    private StartupRecoveryRunner wiredRunner(FrequencyInverterDevice wiredDevice) {
        DeviceService deviceService = mock(DeviceService.class);
        when(deviceService.getFrequencyInverter()).thenReturn(wiredDevice);
        @SuppressWarnings("unchecked")
        ObjectProvider<DeviceService> deviceServices = mock(ObjectProvider.class);
        when(deviceServices.getIfAvailable()).thenReturn(deviceService);
        return new StartupRecoveryRunner(repository, mapper, new HardwareModeInfo("real"), deviceServices, true);
    }

    @Test
    void theWiredProbeAsksTheDriveAndSkipsWhenItReportsLocal() throws Exception {
        when(repository.findByRunStatusIn(anyCollection())).thenReturn(List.of());
        when(drive.readControlSource()).thenReturn(ControlSource.LOCAL);

        wiredRunner(device).onApplicationReady(null);
        awaitDeEnergize();

        verify(drive).readControlSource();
        verify(drive, never()).stop();
        verify(drive, never()).setGeneralEnable(anyBoolean());
        verify(drive, never()).setSpeedReferenceValueAsRpm(anyInt());
        verify(drive, never()).setStart(anyBoolean());
        verify(drive).close();
    }

    @Test
    void aPluginOlderThanTheControlSourceQueryStillDeEnergizes() throws Exception {
        // Built against device-api < 1.3.0: the contract's default answers UNKNOWN.
        when(repository.findByRunStatusIn(anyCollection())).thenReturn(List.of());
        when(drive.readControlSource()).thenCallRealMethod();

        wiredRunner(device).onApplicationReady(null);
        awaitDeEnergize();

        verify(drive).stop();
    }

    @Test
    void aFailedControlSourceReadStillDeEnergizes() throws Exception {
        when(repository.findByRunStatusIn(anyCollection())).thenReturn(List.of());
        when(drive.readControlSource()).thenThrow(new RuntimeException("Timeout"));

        wiredRunner(device).onApplicationReady(null);
        awaitDeEnergize();

        verify(drive).stop();
    }

    @ParameterizedTest
    @NullSource
    @EnumSource(value = ControlSource.class, names = {"REMOTE", "UNKNOWN"})
    void everyAnswerButLocalDeEnergizes(ControlSource source) throws Exception {
        when(repository.findByRunStatusIn(anyCollection())).thenReturn(List.of());

        runner("real", source).onApplicationReady(null);
        awaitDeEnergize();

        verify(drive).stop();
    }

    /** End to end over the simulated drive, whose control source the bench holds. */
    @ParameterizedTest
    @EnumSource(ControlSource.class)
    void theSimulatedDriveIsDeEnergizedUnlessItReportsLocal(ControlSource source) throws Exception {
        when(repository.findByRunStatusIn(anyCollection())).thenReturn(List.of());
        // Never started, so no plant tick thread runs and nothing needs stopping.
        SimulatedFaultSwitches faults = new SimulatedFaultSwitches();
        SimulatedBench bench = new SimulatedBench(new SimulatedBenchProperties(), faults);
        SimulatedDriveProvider provider = new SimulatedDriveProvider(bench, faults);
        Drive operator = provider.open();
        operator.setGeneralEnable(true);
        operator.setStart(true);
        bench.setControlSource(source);

        wiredRunner(new FrequencyInverterDevice(provider)).onApplicationReady(null);
        awaitDeEnergize();

        Map<String, Boolean> control = provider.open().getControlParameters();
        boolean leftAlone = source == ControlSource.LOCAL;
        assertThat(control.get("generalEnable")).isEqualTo(leftAlone);
        assertThat(control.get("start")).isEqualTo(leftAlone);
    }

    @Test
    void aThrowingStopStillReachesTheRelease() throws Exception {
        // Best-effort de-energize: a switched-off bench errors the stop, and the handle release
        // still runs.
        when(repository.findByRunStatusIn(anyCollection())).thenReturn(List.of());
        doThrow(new RuntimeException("no comms")).when(drive).stop();
        doThrow(new RuntimeException("close failed too")).when(drive).close();

        runner("real").onApplicationReady(null);
        awaitDeEnergize();

        verify(drive).stop();
        verify(drive).close();
        assertThat(device.isDriveHandleOpen()).isFalse();
    }

    @Test
    void aStopThatMissesItsDeadlineIsLoggedAndTheHandleReleased() throws Exception {
        // A gated driver still sends a STOP that missed its deadline once the transaction ahead ends.
        when(repository.findByRunStatusIn(anyCollection())).thenReturn(List.of());
        doThrow(new DriveDeadlineException(DriveLane.STOP, "stop not answered within 1500 ms"))
                .when(drive).stop();

        assertThatCode(() -> runner("real").onApplicationReady(null)).doesNotThrowAnyException();
        awaitDeEnergize();

        verify(drive).stop();
        verify(drive).close();
        assertThat(BootDeEnergizeGate.isPending()).isFalse();
    }

    @Test
    void aFailingOpenOpensTheGateWithoutThrowing() throws Exception {
        when(repository.findByRunStatusIn(anyCollection())).thenReturn(List.of());
        when(driveProvider.open()).thenThrow(new IllegalStateException("bench switched off"));

        runner("real").onApplicationReady(null);
        awaitDeEnergize();

        verifyNoInteractions(drive);
    }

    @Test
    void anAlreadyOpenHandleIsSharedNeverASecondSession() throws Exception {
        // H-13: a private handle put a second USB session on the drive. A holder that opened the
        // device first must see its handle reused and left open.
        when(repository.findByRunStatusIn(anyCollection())).thenReturn(List.of());
        device.connect();

        runner("real").onApplicationReady(null);
        awaitDeEnergize();

        verify(driveProvider, times(1)).open();
        verify(drive).stop();
        verify(drive, never()).close();
        assertThat(device.isDriveHandleOpen()).isTrue();
        device.disconnect();
    }

    @Test
    void theDeEnergizeWaitsForATierTwoSessionToEnd() throws Exception {
        // Its connect() takes the device's monitor, which a safe stop's tier 2 holds across its fresh
        // session, so the boot never opens or shares a handle beside that session (OQ-50).
        when(repository.findByRunStatusIn(anyCollection())).thenReturn(List.of());
        CountDownLatch holding = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Thread holder = Thread.ofPlatform().start(() -> device.runExclusive(() -> {
            holding.countDown();
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }));
        assertThat(holding.await(5, TimeUnit.SECONDS)).isTrue();

        runner("real").onApplicationReady(null);

        verify(drive, after(300).never()).stop();
        verifyNoInteractions(driveProvider);
        release.countDown();
        holder.join(5000);
        awaitDeEnergize();
        verify(drive).stop();
    }

    @Test
    void theGateHoldsStartsFromConstructionUntilTheDeEnergizeIsDone() throws Exception {
        when(repository.findByRunStatusIn(anyCollection())).thenReturn(List.of());
        CountDownLatch inDeEnergize = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(invocation -> {
            inDeEnergize.countDown();
            release.await(5, TimeUnit.SECONDS);
            return null;
        }).when(drive).stop();

        StartupRecoveryRunner runner = runner("real");
        // Before ApplicationReadyEvent: the web server is already taking requests by then.
        assertThat(BootDeEnergizeGate.isPending()).isTrue();

        runner.onApplicationReady(null);
        assertThat(inDeEnergize.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(BootDeEnergizeGate.isPending()).isTrue();

        release.countDown();
        awaitDeEnergize();
        assertThat(BootDeEnergizeGate.isPending()).isFalse();
    }
}
