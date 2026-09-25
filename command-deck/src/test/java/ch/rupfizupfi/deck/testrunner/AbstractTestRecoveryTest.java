package ch.rupfizupfi.deck.testrunner;

import ch.rupfizupfi.deck.data.TestResult;
import ch.rupfizupfi.deck.device.DeviceService;
import ch.rupfizupfi.deck.filesystem.CSVStoreService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyFloat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** What {@code AbstractTest}'s sensor-loss path hands its collaborators. */
class AbstractTestRecoveryTest {

    private static final double FLOOR_NEWTON = 750;

    private SensorReconnector reconnector;
    private RecoveryGates gates;
    private StubTest test;

    @BeforeEach
    void wireRun() {
        reconnector = mock(SensorReconnector.class);
        when(reconnector.attemptRecovery(anyString(), anyFloat(), anyDouble()))
                .thenReturn(new SensorReconnector.Outcome(false, 1, "stubbed recovery", null));

        RecoveryProperties properties = new RecoveryProperties();
        properties.setMinEnvelopeNewton(FLOOR_NEWTON);
        gates = properties.snapshot();

        TestResult testResult = new TestResult();
        testResult.setId(7L);
        test = new StubTest(testResult, mock(TestLogger.class), mock(TestRunnerFactory.class),
                mock(DeviceService.class), mock(MotorSafetyController.class));
        wireRunner(null);
        test.setReconnector(reconnector);
    }

    /** Re-wires the run around this runner; every other collaborator stays per-run and fresh. */
    private void wireRunner(TestRunnerThread runner) {
        test.initRecovery(runner, new TestStateMachine(), mock(GapRecorder.class),
                new CSVStoreService.TestRunFiles("force.csv", "gaps.json"), gates);
    }

    @Test
    void nearZeroLimitsAreGatedAgainstTheFloorNotAgainstAnUnpassableFewNewton() {
        assertThat(envelopeAfterLoss(100.0, -100.0)).isEqualTo(FLOOR_NEWTON);
    }

    @Test
    void anEnvelopeWiderThanTheFloorIsUsedAsConfigured() {
        assertThat(envelopeAfterLoss(5000.0, -500.0)).isEqualTo(5000.0);
    }

    @Test
    void theIncidentIsOpenedBeforeItIsAnnouncedAndLongBeforeItsVerdictArrives() {
        TestRunnerThread runner = mock(TestRunnerThread.class);
        wireRunner(runner);
        test.testContext = new TestContext(1L, 100.0, -100.0);

        test.onSensorLoss("no measurement for 250 ms", 12f);

        InOrder order = inOrder(runner);
        order.verify(runner).beginIncident();
        // The reconnector runs on its own thread, so only this call is awaited.
        order.verify(runner, timeout(2000)).recordRecoveryOutcome(any());
    }

    /** Loses the sensor on a run configured with these limits and returns the envelope gated on. */
    private double envelopeAfterLoss(double upperLimit, double lowerLimit) {
        test.testContext = new TestContext(1L, upperLimit, lowerLimit);
        test.onSensorLoss("no measurement for 250 ms", 12f);

        ArgumentCaptor<Double> envelope = ArgumentCaptor.forClass(Double.class);
        // Recovery runs on its own thread, so the call is awaited rather than expected to have
        // happened already.
        verify(reconnector, timeout(2000))
                .attemptRecovery(anyString(), anyFloat(), envelope.capture());
        return envelope.getValue();
    }

    private static final class StubTest extends AbstractTest {
        StubTest(TestResult testResult, TestLogger testLogger, TestRunnerFactory testRunnerFactory,
                 DeviceService deviceService, MotorSafetyController motorSafety) {
            super(testResult, testLogger, testRunnerFactory, deviceService, motorSafety);
        }

        @Override
        void setup() {
        }

        @Override
        public void handleSignal(int signal) {
        }
    }
}
