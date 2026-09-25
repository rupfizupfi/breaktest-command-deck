package ch.rupfizupfi.deck.testrunner;

import ch.rupfizupfi.deck.data.TestParameter;
import ch.rupfizupfi.deck.data.TestResult;
import ch.rupfizupfi.deck.device.HardwareModeInfo;
import ch.rupfizupfi.deck.filesystem.CSVStoreService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.mockito.ArgumentCaptor;

import java.io.IOException;
import java.lang.reflect.Field;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TestRunnerThreadTest {

    /** Long enough for the runner's own 50 ms startup sleep plus a loaded CI machine. */
    private static final long RUN_END_TIMEOUT_MS = 5000;

    private TestRunnerFactory testRunnerFactory;
    private MotorSafetyController motorSafetyController;
    private TestLogger testLogger;
    private TestRunnerThread runner;

    @BeforeEach
    void wireRunner() {
        testRunnerFactory = mock(TestRunnerFactory.class);
        motorSafetyController = mock(MotorSafetyController.class);
        testLogger = mock(TestLogger.class);

        when(testRunnerFactory.createLogger(any())).thenReturn(testLogger);
        when(motorSafetyController.safeStop(anyString())).thenReturn(
                new SafeStopResult(SafeStopResult.Tier.EXISTING_HANDLE, true, true, true, 0, "standstill"));

        runner = new TestRunnerThread(testRunnerFactory, motorSafetyController,
                new HardwareModeInfo("simulated"));
    }

    @Test
    void startRefusesTheRunWhenTheLogFileCannotBeOpened() throws IOException {
        doThrow(new IOException("result directory is read-only")).when(testLogger).begin();

        assertThatThrownBy(() -> runner.startThread(testResult("destructive")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Could not start the run");

        assertThat(runner.isRunning()).isFalse();
        // No state machine and no runner thread: the run never got past its own logger.
        assertThat(runner.snapshot()).isNull();
        verify(testRunnerFactory, never()).createRunFiles(anyLong());
        verify(testRunnerFactory, never()).createStateBroadcaster(anyLong(), any(), any());
    }

    @Test
    void aRefusedStartClosesTheRunLog() {
        wireRunScope();
        when(testRunnerFactory.createGapRecorder(anyLong(), any(), any()))
                .thenThrow(new IllegalStateException("result directory vanished"));

        assertThatThrownBy(() -> runner.startThread(testResult("destructive")))
                .isInstanceOf(IllegalStateException.class);

        assertThat(runner.isRunning()).isFalse();
        // The log was already open when the start was refused, so the refusal owns closing it.
        verify(testLogger, times(1)).end();

        // Nothing is running, so an operator Stop adds no second close.
        runner.stopThread();
        verify(testLogger, times(1)).end();
    }

    @Test
    @Timeout(15)
    void unrunnableParameterTypeFaultsTheRunNamingTheType() throws Exception {
        wireRunScope();

        runner.startThread(testResult("nonsense"));
        awaitRunEnd();

        ArgumentCaptor<String> lines = ArgumentCaptor.forClass(String.class);
        verify(testLogger, atLeastOnce()).log(lines.capture());
        assertThat(lines.getAllValues())
                .anyMatch(line -> line.contains("unknown test parameter type: nonsense"));

        assertThat(runner.snapshot()).isNotNull();
        assertThat(runner.snapshot().state()).isEqualTo(TestState.FAULT);
        verify(testLogger, times(1)).end();

        // Nothing is running any more, so an operator Stop arriving late adds no second close.
        runner.stopThread();
        verify(testLogger, times(1)).end();
    }

    @Test
    void retryShutdownStopsTheMotorWhenDestroyKeepsThrowing() throws Exception {
        AbstractTest failingTest = mock(AbstractTest.class);
        doThrow(new IllegalStateException("USB handle gone")).when(failingTest).destroy();
        setCurrentTest(failingTest);

        runner.retryShutdownOnException();

        verify(motorSafetyController).safeStop("cleanup failed");
    }

    @Test
    void aGapRecordNamesTheStopOfItsOwnIncident() throws Exception {
        GapRecorder recorder = mock(GapRecorder.class);
        setField("gapRecorder", recorder);
        setField("incidentStopTier", "EXISTING_HANDLE");
        setField("incidentStopVerified", Boolean.TRUE);
        when(motorSafetyController.getLastStopResult()).thenReturn(
                new SafeStopResult(SafeStopResult.Tier.OPERATOR_ESCALATION, false, false, true, null, "silent"));

        runner.endGap("RESUMED");
        verify(recorder).endGap(eq("RESUMED"), any(), any(), any(), anyInt(), eq("EXISTING_HANDLE"), eq(true));

        // The capture belonged to the first incident, so the next gap reads the controller itself.
        runner.endGap("ABORTED");
        verify(recorder).endGap(eq("ABORTED"), any(), any(), any(), anyInt(), eq("OPERATOR_ESCALATION"), eq(false));
    }

    @Test
    void aGapRecordNamesTheRecoveryOfItsOwnIncident() throws Exception {
        GapRecorder recorder = mock(GapRecorder.class);
        setField("gapRecorder", recorder);
        runner.recordRecoveryOutcome(new SensorReconnector.Outcome(true, 3, "stubbed",
                new LoadCellThread.GateResult(true, 12f, 1.5f, 0.01, "ok")));

        runner.endGap("RESUMED");
        verify(recorder).endGap(eq("RESUMED"), eq(12f), eq(1.5f), eq(0.01), eq(3), any(), any());

        // The verdict belonged to the first incident, so a second loss that aborts before its own
        // reconnector reports carries no gate numbers and no attempts.
        runner.beginIncident();
        runner.endGap("ABORTED");
        verify(recorder).endGap(eq("ABORTED"), isNull(), isNull(), isNull(), eq(0), any(), any());
    }

    @Test
    @Timeout(15)
    void startClearsAnEarlierRunsCapturedStop() throws Exception {
        setField("incidentStopTier", "FRESH_HANDLE");
        setField("incidentStopVerified", Boolean.FALSE);
        wireRunScope();

        // The unknown type faults before any gap opens, so only startThread can have cleared them.
        runner.startThread(testResult("nonsense"));
        awaitRunEnd();

        assertThat(getField("incidentStopTier")).isNull();
        assertThat(getField("incidentStopVerified")).isNull();
    }

    /** Everything {@code startThread} pulls from the factory for a run that reaches its runner thread. */
    private void wireRunScope() {
        when(testRunnerFactory.recoveryProperties()).thenReturn(new RecoveryProperties());
        when(testRunnerFactory.createRunFiles(anyLong()))
                .thenReturn(new CSVStoreService.TestRunFiles("force.csv", "gaps.json"));
        when(testRunnerFactory.createGapRecorder(anyLong(), any(), any())).thenReturn(mock(GapRecorder.class));
        when(testRunnerFactory.createStateBroadcaster(anyLong(), any(), any()))
                .thenReturn(mock(TestStateBroadcaster.class));
        when(testRunnerFactory.createStatusPersister(anyLong(), any(), any(), any(), any()))
                .thenReturn(mock(TestResultStatusPersister.class));
    }

    private TestResult testResult(String type) {
        TestParameter parameter = new TestParameter();
        parameter.type = type;

        TestResult result = new TestResult();
        result.setId(7L);
        result.testParameter = parameter;
        return result;
    }

    /** {@code running} is cleared last in the runner's finally, after the log has been closed. */
    private void awaitRunEnd() throws InterruptedException {
        await(() -> !runner.isRunning());
    }

    private void await(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.currentTimeMillis() + RUN_END_TIMEOUT_MS;
        while (!condition.getAsBoolean() && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
        assertThat(condition.getAsBoolean()).isTrue();
    }

    /** The run's current test is owned by the runner and only ever set by its own start path. */
    private void setCurrentTest(AbstractTest test) throws Exception {
        setField("test", test);
    }

    /** Run-scoped state the runner only ever assigns itself; reflection is how a test can stage it. */
    private void setField(String name, Object value) throws Exception {
        Field field = TestRunnerThread.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(runner, value);
    }

    private Object getField(String name) throws Exception {
        Field field = TestRunnerThread.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(runner);
    }
}
