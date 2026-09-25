package ch.rupfizupfi.deck.testrunner;

import ch.rupfizupfi.deck.device.loadcell.LoadCellDevice;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyFloat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The reconnect loop's verdicts against a scripted device. The millisecond gates below are test
 * fixtures chosen to keep the suite fast, never plant parameters - those live in
 * {@link RecoveryProperties}.
 */
class SensorReconnectorTest {

    private static final float LAST_KNOWN_FORCE = 12.5f;
    private static final double ENVELOPE_NEWTON = 200.0;

    /**
     * {@link SensorReconnector}'s own bound for a 100 ms window and a 100 ms gate: its fresh-data
     * slice plus two gate margins on top.
     */
    private static final long WEDGED_VERDICT_BOUND_MS = 100 + 100 + 1000 + 2 * 2000;

    private final LoadCellDevice loadCellDevice = mock(LoadCellDevice.class);
    private final LoadCellThread loadCellThread = mock(LoadCellThread.class);

    /** Releases any reset parked in {@link #wedgeReset}, so no reconnect thread outlives a test. */
    private final CountDownLatch resetRelease = new CountDownLatch(1);

    private SensorReconnector reconnector;

    @AfterEach
    void releaseTheReconnector() {
        resetRelease.countDown();
        if (reconnector != null) {
            reconnector.shutdownNow();
        }
    }

    @Test
    @Timeout(20)
    void recoversWhenTheFirstResetDeliversDataAndTheGatePasses() {
        SensorReconnector r = reconnectorWith(gates(500, List.of(20L), 100));
        when(loadCellDevice.awaitFreshMeasurement(anyLong())).thenReturn(true);
        gateReturns(passedGate());

        SensorReconnector.Outcome outcome = r.attemptRecovery("no data", LAST_KNOWN_FORCE, ENVELOPE_NEWTON);

        assertThat(outcome.recovered()).isTrue();
        assertThat(outcome.attempts()).isEqualTo(1);
        assertThat(outcome.gate()).isNotNull();
        assertThat(outcome.gate().passed()).isTrue();
        verify(loadCellThread, times(1)).markSensorRecovered();
        verify(loadCellThread).beginRecoveryGate(eq(LAST_KNOWN_FORCE), eq(ENVELOPE_NEWTON));
    }

    @Test
    @Timeout(20)
    void aFailedGateIsTerminalAndNotRetried() {
        SensorReconnector r = reconnectorWith(gates(2000, List.of(20L), 100));
        when(loadCellDevice.awaitFreshMeasurement(anyLong())).thenReturn(true);
        gateReturns(new LoadCellThread.GateResult(false, 90f, 80f, 0.4, "drift 40 % of envelope"));

        SensorReconnector.Outcome outcome = r.attemptRecovery("frozen", LAST_KNOWN_FORCE, ENVELOPE_NEWTON);

        assertThat(outcome.recovered()).isFalse();
        assertThat(outcome.attempts()).isEqualTo(1);
        assertThat(outcome.gate()).isNotNull();
        assertThat(outcome.gate().passed()).isFalse();
        verify(loadCellDevice, times(1)).reset();
    }

    @Test
    @Timeout(20)
    void noFreshDataRetriesUntilTheWindowExpiresAndRepeatsTheLastBackoff() {
        SensorReconnector r = reconnectorWith(gates(300, List.of(20L, 20L), 100));
        when(loadCellDevice.awaitFreshMeasurement(anyLong())).thenReturn(false);

        SensorReconnector.Outcome outcome = r.attemptRecovery("no data", LAST_KNOWN_FORCE, ENVELOPE_NEWTON);

        assertThat(outcome.recovered()).isFalse();
        assertThat(outcome.attempts()).isGreaterThanOrEqualTo(2);
        assertThat(outcome.gate()).isNull();
        assertThat(outcome.detail()).contains("delivered no measurement");
    }

    @Test
    @Timeout(20)
    void aThrowingResetEndsTheAttemptNotTheRecovery() {
        SensorReconnector r = reconnectorWith(gates(2000, List.of(20L), 100));
        doThrow(new UnsatisfiedLinkError("dscusb")).doNothing().when(loadCellDevice).reset();
        when(loadCellDevice.awaitFreshMeasurement(anyLong())).thenReturn(true);
        gateReturns(passedGate());

        SensorReconnector.Outcome outcome = r.attemptRecovery("link error", LAST_KNOWN_FORCE, ENVELOPE_NEWTON);

        assertThat(outcome.recovered()).isTrue();
        assertThat(outcome.attempts()).isEqualTo(2);
    }

    @Test
    @Timeout(20)
    void aWedgedResetNeverBlocksTheCallerPastTheBound() {
        SensorReconnector r = reconnectorWith(gates(100, List.of(20L), 100));
        wedgeReset(new CountDownLatch(1));

        long startNanos = System.nanoTime();
        SensorReconnector.Outcome outcome = r.attemptRecovery("wedged", LAST_KNOWN_FORCE, ENVELOPE_NEWTON);
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);

        assertThat(outcome.recovered()).isFalse();
        assertThat(outcome.detail()).contains("no verdict");
        assertThat(elapsedMs).isLessThan(WEDGED_VERDICT_BOUND_MS + 3000);
    }

    @Test
    @Timeout(20)
    void shutdownNowReleasesAQueuedAttempt() throws Exception {
        SensorReconnector r = reconnectorWith(gates(100, List.of(20L), 100));
        CountDownLatch resetEntered = new CountDownLatch(1);
        wedgeReset(resetEntered);

        startCaller("first-caller", r);
        assertThat(resetEntered.await(5, TimeUnit.SECONDS)).isTrue();
        CompletableFuture<SensorReconnector.Outcome> queued = startCaller("queued-caller", r);

        r.shutdownNow();

        SensorReconnector.Outcome outcome = queued.get(2, TimeUnit.SECONDS);
        assertThat(outcome.recovered()).isFalse();
    }

    private SensorReconnector reconnectorWith(RecoveryGates gates) {
        reconnector = new SensorReconnector(loadCellDevice, loadCellThread, gates);
        return reconnector;
    }

    private RecoveryGates gates(long reconnectWindowMillis, List<Long> backoffMillis,
                                long plausibilityGateMillis) {
        return new RecoveryGates(reconnectWindowMillis, backoffMillis, 1000, 1000,
                plausibilityGateMillis, 0.02, 2, 100.0, true);
    }

    private LoadCellThread.GateResult passedGate() {
        return new LoadCellThread.GateResult(true, 12.0f, 0.5f, 0.0025, "stable for the gate window");
    }

    private void gateReturns(LoadCellThread.GateResult gate) {
        when(loadCellThread.beginRecoveryGate(anyFloat(), anyDouble()))
                .thenReturn(CompletableFuture.completedFuture(gate));
    }

    /** Parks reset() the way a native USB call does: until {@link #resetRelease}, interrupts ignored. */
    private void wedgeReset(CountDownLatch entered) {
        doAnswer(invocation -> {
            entered.countDown();
            boolean released = false;
            while (!released) {
                try {
                    resetRelease.await();
                    released = true;
                } catch (InterruptedException ignored) {
                    // The wedge is the point of the fixture.
                }
            }
            return null;
        }).when(loadCellDevice).reset();
    }

    private CompletableFuture<SensorReconnector.Outcome> startCaller(String name, SensorReconnector r) {
        CompletableFuture<SensorReconnector.Outcome> result = new CompletableFuture<>();
        Thread caller = new Thread(
                () -> result.complete(r.attemptRecovery(name, LAST_KNOWN_FORCE, ENVELOPE_NEWTON)), name);
        caller.setDaemon(true);
        caller.start();
        return result;
    }
}
