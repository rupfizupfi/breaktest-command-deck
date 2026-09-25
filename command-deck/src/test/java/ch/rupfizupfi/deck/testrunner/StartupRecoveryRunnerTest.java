package ch.rupfizupfi.deck.testrunner;

import ch.rupfizupfi.deck.data.RunStatus;
import ch.rupfizupfi.deck.data.TestResult;
import ch.rupfizupfi.deck.data.TestResultRepository;
import ch.rupfizupfi.deck.device.HardwareModeInfo;
import ch.rupfizupfi.deck.device.api.Drive;
import ch.rupfizupfi.deck.device.api.DriveProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
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
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The synchronous sweep, plus the de-energize that follows it. The de-energize runs on a detached
 * daemon thread ("startup-drive-deenergize") because a drive open can wedge forever, so the tests
 * that cross into it synchronize on the stubbed drive's {@code close()} — the thread's last drive
 * call — with a bounded await, never a sleep.
 */
class StartupRecoveryRunnerTest {

    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {
    };

    private final TestResultRepository repository = mock(TestResultRepository.class);
    @SuppressWarnings("unchecked")
    private final ObjectProvider<DriveProvider> driveProviders = mock(ObjectProvider.class);
    private final ObjectMapper mapper = new ObjectMapper();

    private StartupRecoveryRunner runner(String hardwareMode) {
        return new StartupRecoveryRunner(repository, mapper, new HardwareModeInfo(hardwareMode),
                driveProviders);
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
        // A real crashed run's document: TestResultStatusPersister's LogDocument. The marker
        // goes into `events`, deliberately a separate key from the persister's `transitions`:
        // the sweep never injects synthetic entries into the state machine's own record.
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
        // The one real producer of an `events` array today is this sweep itself, so the merge
        // into an existing array is exercised by sweeping the same row twice - a row whose
        // result was re-run after a swept boot and then orphaned again.
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
    void zeroOrphansNeverConsultTheDriveProvider() {
        when(repository.findByRunStatusIn(anyCollection())).thenReturn(List.of());

        runner("real").onApplicationReady(null);

        verify(repository, never()).save(any());
        verifyNoInteractions(driveProviders);
    }

    @Test
    void simulatedModeShortCircuitsBeforeAnyDriveAccess() {
        when(repository.findByRunStatusIn(anyCollection()))
                .thenReturn(List.of(orphan(1L, RunStatus.IN_PROGRESS, null)));

        runner("simulated").onApplicationReady(null);

        verifyNoInteractions(driveProviders);
    }

    @Test
    void missingDriveProviderBeanWarnsInsteadOfThrowing() {
        // A driver jar absent at launch means no provider bean at all; boot recovery must
        // not turn that into a failure. With a null provider the de-energize thread is
        // never started, so this test stays synchronous.
        TestResult row = orphan(1L, RunStatus.IN_PROGRESS, null);
        when(repository.findByRunStatusIn(anyCollection())).thenReturn(List.of(row));
        when(driveProviders.getIfAvailable()).thenReturn(null);

        assertThatCode(() -> runner("real").onApplicationReady(null))
                .doesNotThrowAnyException();

        verify(driveProviders).getIfAvailable();
        // The sweep is the part that must always happen, and it already has.
        assertThat(row.runStatus).isEqualTo(RunStatus.ABORTED);
    }

    @Test
    void failingQuerySkipsRecoveryWithoutThrowing() {
        when(repository.findByRunStatusIn(anyCollection()))
                .thenThrow(new RuntimeException("database not reachable"));

        assertThatCode(() -> runner("real").onApplicationReady(null))
                .doesNotThrowAnyException();

        verify(repository, never()).save(any());
        verifyNoInteractions(driveProviders);
    }

    @Test
    void deEnergizeDropsGeneralEnableFirstThenSpeedThenStartThenCloses() throws Exception {
        when(repository.findByRunStatusIn(anyCollection()))
                .thenReturn(List.of(orphan(1L, RunStatus.IN_PROGRESS, null)));
        Drive drive = mock(Drive.class);
        CountDownLatch closed = new CountDownLatch(1);
        doAnswer(invocation -> {
            closed.countDown();
            return null;
        }).when(drive).close();
        armRealDrive(drive);

        runner("real").onApplicationReady(null);

        assertThat(closed.await(5, TimeUnit.SECONDS))
                .as("the startup-drive-deenergize thread reached close()")
                .isTrue();
        // General enable FIRST: dropping it de-energizes the output stage and lets the motor
        // coast (the coast-never-reverse rule) before any reference or start bit changes.
        InOrder order = inOrder(drive);
        order.verify(drive).setGeneralEnable(false);
        order.verify(drive).setSpeedReferenceValueAsRpm(0);
        order.verify(drive).setStart(false);
        order.verify(drive).close();
    }

    @Test
    void everyDriveCallThrowingStillReachesEveryRemainingCallAndClose() throws Exception {
        // Best-effort de-energize: a switched-off bench errors every register write, and every
        // remaining write plus close() still runs.
        when(repository.findByRunStatusIn(anyCollection()))
                .thenReturn(List.of(orphan(1L, RunStatus.IN_PROGRESS, null)));
        Drive drive = mock(Drive.class);
        doThrow(new RuntimeException("no comms")).when(drive).setGeneralEnable(false);
        doThrow(new RuntimeException("no comms")).when(drive).setSpeedReferenceValueAsRpm(0);
        doThrow(new RuntimeException("no comms")).when(drive).setStart(false);
        CountDownLatch closed = new CountDownLatch(1);
        doAnswer(invocation -> {
            closed.countDown();
            throw new RuntimeException("close failed too");
        }).when(drive).close();
        armRealDrive(drive);

        runner("real").onApplicationReady(null);

        assertThat(closed.await(5, TimeUnit.SECONDS))
                .as("close() is reached even when every drive call throws")
                .isTrue();
        verify(drive).setGeneralEnable(false);
        verify(drive).setSpeedReferenceValueAsRpm(0);
        verify(drive).setStart(false);
    }

    /** Wires the mocked ObjectProvider to hand out a provider whose open() returns this drive. */
    private void armRealDrive(Drive drive) {
        DriveProvider provider = mock(DriveProvider.class);
        when(provider.open()).thenReturn(drive);
        when(driveProviders.getIfAvailable()).thenReturn(provider);
    }
}
