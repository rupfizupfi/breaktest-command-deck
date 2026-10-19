package ch.rupfizupfi.deck.testrunner;

import ch.rupfizupfi.deck.data.TestParameter;
import ch.rupfizupfi.deck.data.TestResult;
import ch.rupfizupfi.deck.data.TestType;
import ch.rupfizupfi.deck.device.HardwareModeInfo;
import ch.rupfizupfi.deck.filesystem.CSVStoreService;
import ch.rupfizupfi.deck.rig.RigLimits;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.mockito.ArgumentCaptor;


import java.io.IOException;
import java.lang.reflect.Field;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
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

    @AfterEach
    void leaveTheBootGateOpen() {
        BootDeEnergizeGate.resetForTests();
    }

    @Test
    void aStartDuringTheBootDeEnergizeIsRefusedBeforeItTouchesAnything() {
        // H-13: a run started now would be de-energized mid-setup and hang at zero force.
        BootDeEnergizeGate.begin();
        runner.bootDeEnergizeWaitMs = 100;

        assertThatThrownBy(() -> runner.startThread(runnableDestructive()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("bench is still initialising");

        assertThat(runner.isRunning()).isFalse();
        assertThat(runner.snapshot()).isNull();
        verify(motorSafetyController, never()).clearStopLatch();
        verify(testRunnerFactory, never()).createLogger(any());
    }

    @Test
    @Timeout(15)
    void aStartWaitsOutABootDeEnergizeThatFinishesWithinTheBound() throws Exception {
        wireRunScope();
        CountDownLatch token = BootDeEnergizeGate.begin();
        Thread.ofPlatform().start(() -> {
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            BootDeEnergizeGate.complete(token);
        });

        runner.startThread(testResult(null));

        assertThat(BootDeEnergizeGate.isPending()).isFalse();
        verify(motorSafetyController).clearStopLatch();
        awaitRunEnd();
    }

    /** I15: per-run state is reset at start, and a Start is refused while the last run's stop executes. */
    @Test
    void aStartIsRefusedWhileTheLastRunsStopIsStillExecuting() {
        doThrow(new IllegalStateException("refusing to clear the motor safety stop latch, a stop is still executing"))
                .when(motorSafetyController).clearStopLatch();

        assertThatThrownBy(() -> runner.startThread(runnableDestructive()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("a stop is still executing");

        assertThat(runner.isRunning()).isFalse();
        verify(testRunnerFactory, never()).createLogger(any());
    }

    @Test
    void startRefusesTheRunWhenTheLogFileCannotBeOpened() throws IOException {
        doThrow(new IOException("result directory is read-only")).when(testLogger).begin();

        assertThatThrownBy(() -> runner.startThread(testResult(TestType.DESTRUCTIVE)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Could not start the run");

        assertThat(runner.isRunning()).isFalse();
        // No state machine and no runner thread: the run never got past its own logger.
        assertThat(runner.snapshot()).isNull();
        verify(testRunnerFactory, never()).createRunFiles(anyLong());
        verify(testRunnerFactory, never()).createStateBroadcaster(anyLong(), any());
    }

    @Test
    void aRefusedStartClosesTheRunLog() {
        wireRunScope();
        when(testRunnerFactory.createGapRecorder(anyLong(), any(), any()))
                .thenThrow(new IllegalStateException("result directory vanished"));

        assertThatThrownBy(() -> runner.startThread(testResult(TestType.DESTRUCTIVE)))
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
    void anUntypedParameterFaultsTheRunNamingTheMissingType() throws Exception {
        // The one unrunnable parameter left (I21): a stored value outside the three fails the load
        // in TestTypeConverter and never reaches a runner.
        wireRunScope();

        runner.startThread(testResult(null));
        awaitRunEnd();

        ArgumentCaptor<String> lines = ArgumentCaptor.forClass(String.class);
        verify(testLogger, atLeastOnce()).log(lines.capture());
        assertThat(lines.getAllValues())
                .anyMatch(line -> line.contains("unknown test parameter type: null"));

        assertThat(runner.snapshot()).isNotNull();
        assertThat(runner.snapshot().state()).isEqualTo(TestState.FAULT);
        verify(testLogger, times(1)).end();

        // Nothing is running any more, so an operator Stop arriving late adds no second close.
        runner.stopThread();
        verify(testLogger, times(1)).end();
    }

    @Test
    @Timeout(15)
    void aStopDuringStartupAbortsTheRunAndLatchesBeforeAnythingCanEnergize() throws Exception {
        wireRunScope();
        CountDownLatch inStartup = blockRunnerInStartup();

        runner.startThread(runnableDestructive());
        assertThat(inStartup.await(5, TimeUnit.SECONDS)).isTrue();

        runner.stopThread();

        // STARTING -> STOPPING is illegal; before the fix the Stop was dropped and setup() energized.
        assertThat(runner.snapshot().state()).isEqualTo(TestState.ABORTED);
        verify(motorSafetyController).latchStop(anyString());
        awaitRunEnd();
    }

    @Test
    @Timeout(15)
    void aSecondResultIsRefusedWhileARunIsActiveButTheSameOneIsIdempotent() throws Exception {
        wireRunScope();
        CountDownLatch inStartup = blockRunnerInStartup();
        runner.startThread(runnableDestructive());
        assertThat(inStartup.await(5, TimeUnit.SECONDS)).isTrue();

        // A remount of the view that started it (React StrictMode does this) must not throw.
        runner.startThread(runnableDestructive());
        TestResult other = runnableDestructive();
        other.setId(8L);
        assertThatThrownBy(() -> runner.startThread(other))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("run 7 is active");
        assertThat(runner.isActiveRun(7L)).isTrue();
        assertThat(runner.isActiveRun(8L)).isFalse();

        runner.stopThread();
        awaitRunEnd();
    }

    /**
     * H-16: a run that ends before startThread returns must leave the bench free. The runner thread
     * here runs the whole run inside start(), the worst-case schedule; with running set after
     * start() the run's own end cleared it first and every later Start was refused.
     */
    @Test
    @Timeout(15)
    void aRunThatEndsBeforeStartReturnsLeavesTheBenchFreeForTheNextStart() {
        wireRunScope();
        runner = new TestRunnerThread(testRunnerFactory, motorSafetyController,
                new HardwareModeInfo("simulated")) {
            @Override
            Thread newRunnerThread(Runnable body) {
                return new Thread(body, "TestRunnerThread") {
                    @Override
                    public synchronized void start() {
                        run();
                    }
                };
            }
        };

        // Untyped, so the run faults in STARTING and has fully ended when start() returns.
        runner.startThread(testResult(null));

        assertThat(runner.snapshot().state()).isEqualTo(TestState.FAULT);
        assertThat(runner.isRunning()).isFalse();

        TestResult next = testResult(null);
        next.setId(8L);
        runner.startThread(next);
        assertThat(runner.isRunning()).isFalse();
        verify(testLogger, times(2)).end();
    }

    /** The request-thread answers keep their texts; the run loop only acts on what they accepted. */
    @Test
    void resumeAndAbortRefusalsKeepTheirTexts() throws Exception {
        assertThat(runner.resumeTest("operator").detail()).isEqualTo("no test run is active");
        assertThat(runner.abortTest("operator").detail()).isEqualTo("no test run is active");

        TestStateMachine machine = new TestStateMachine();
        machine.transition(TestState.STARTING, "run requested");
        machine.transition(TestState.RUNNING, "setup complete");
        setField("stateMachine", machine);
        TestRunnerThread.ResumeResponse refused = runner.resumeTest("operator");
        assertThat(refused.accepted()).isFalse();
        assertThat(refused.state()).isEqualTo(TestState.RUNNING);
        assertThat(refused.detail()).isEqualTo("resume is only possible from a safe hold, the run is in RUNNING");

        machine.transition(TestState.FAULT, "boom");
        assertThat(runner.abortTest("operator").detail()).isEqualTo("the run has already ended");
    }

    /** Parks the runner inside createProgram, i.e. in STARTING, until it is interrupted. */
    private CountDownLatch blockRunnerInStartup() {
        CountDownLatch entered = new CountDownLatch(1);
        when(testRunnerFactory.createProgram(any(), any())).thenAnswer(invocation -> {
            entered.countDown();
            new CountDownLatch(1).await();
            return null;
        });
        return entered;
    }

    private TestResult runnableDestructive() {
        TestResult result = testResult(TestType.DESTRUCTIVE);
        result.testParameter.speed = 10;
        result.testParameter.upperShutOffThreshold = 50.0;
        result.testParameter.lowerShutOffThreshold = 1.0;
        return result;
    }

    /** Everything {@code startThread} pulls from the factory for a run that reaches its runner thread. */
    private void wireRunScope() {
        when(testRunnerFactory.recoveryProperties()).thenReturn(new RecoveryProperties());
        when(testRunnerFactory.rigLimits()).thenReturn(RigLimits.DEFAULTS);
        when(testRunnerFactory.createRunFiles(anyLong()))
                .thenReturn(new CSVStoreService.TestRunFiles("force.csv", "gaps.json"));
        when(testRunnerFactory.createGapRecorder(anyLong(), any(), any())).thenReturn(mock(GapRecorder.class));
        when(testRunnerFactory.createStateBroadcaster(anyLong(), any()))
                .thenReturn(mock(TestStateBroadcaster.class));
        when(testRunnerFactory.createStatusPersister(anyLong(), any(), any(), any(), any()))
                .thenReturn(mock(TestResultStatusPersister.class));
    }

    private TestResult testResult(TestType type) {
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

    /** Run-scoped state the runner only ever assigns itself; reflection is how a test can stage it. */
    private void setField(String name, Object value) throws Exception {
        Field field = TestRunnerThread.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(runner, value);
    }
}
