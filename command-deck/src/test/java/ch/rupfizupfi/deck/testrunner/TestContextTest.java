package ch.rupfizupfi.deck.testrunner;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestContextTest {

    private final List<Integer> received = Collections.synchronizedList(new ArrayList<>());
    private TestContext context;
    private Thread pump;

    @BeforeEach
    void createContext() {
        context = new TestContext(1L, 100.0, -100.0);
        context.addSignalListener(received::add);
    }

    @AfterEach
    void stopPump() throws InterruptedException {
        if (pump != null) {
            // processSignals() loops forever by design; interrupting its take() is the only exit.
            pump.interrupt();
            pump.join(2_000);
            assertThat(pump.isAlive()).isFalse();
        }
    }

    private void startPump() {
        pump = new Thread(() -> {
            try {
                context.processSignals();
            } catch (InterruptedException | FinishTestException ignored) {
            }
        }, "test-signal-pump");
        pump.setDaemon(true);
        pump.start();
    }

    /**
     * Signals are enqueued before the pump starts and the queue is FIFO with a single consumer, so
     * once the trailing signal arrives the received list is final and can be asserted exactly.
     */
    private void assertDispatched(Integer... expected) throws InterruptedException {
        startPump();
        awaitSize(received, expected.length);
        assertThat(received).containsExactly(expected);
    }

    private static void awaitSize(List<Integer> list, int size) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5_000;
        while (list.size() < size && System.currentTimeMillis() < deadline) {
            Thread.sleep(5);
        }
    }

    @Test
    void signalZeroIsExemptFromDedup() throws InterruptedException {
        context.sendSignal(0);
        context.sendSignal(0);
        assertDispatched(0, 0);
    }

    @Test
    void repeatedNonzeroSignalIsDedupedToOne() throws InterruptedException {
        context.sendSignal(TestContext.PULL_SIGNAL);
        context.sendSignal(TestContext.PULL_SIGNAL);
        context.sendSignal(0);
        assertDispatched(TestContext.PULL_SIGNAL, 0);
    }

    @Test
    void resetSignalDedupLetsARepeatOfTheLastSignalThrough() throws InterruptedException {
        context.sendSignal(TestContext.PULL_SIGNAL);
        context.resetSignalDedup();
        context.sendSignal(TestContext.PULL_SIGNAL);
        context.sendSignal(0);
        assertDispatched(TestContext.PULL_SIGNAL, TestContext.PULL_SIGNAL, 0);
    }

    @Test
    void drainSignalsDropsTheQueueButDoesNotResetDedup() throws InterruptedException {
        context.sendSignal(TestContext.PULL_SIGNAL);
        context.drainSignals();
        // Still remembered as the last sent signal, so the repeat is swallowed.
        context.sendSignal(TestContext.PULL_SIGNAL);
        context.sendSignal(0);
        assertDispatched(0);
    }

    @Test
    void disabledDispatchStillLetsSignalZeroThrough() throws InterruptedException {
        context.setSignalDispatchEnabled(false);
        context.sendSignal(TestContext.RELEASE_SIGNAL);
        context.sendSignal(0);
        assertDispatched(0);
    }

    @Test
    void signalQueuedWhileDisabledStillFlowsWhenReenabledBeforeDispatch() throws InterruptedException {
        // Disabling gates dispatch at dequeue time, not enqueue: sendSignal still queues, so a
        // signal that survives in the queue until re-enable is delivered. drainSignals() is the
        // purge for signals that must not outlive an incident.
        context.setSignalDispatchEnabled(false);
        context.sendSignal(TestContext.PULL_SIGNAL);
        context.setSignalDispatchEnabled(true);
        context.sendSignal(0);
        assertDispatched(TestContext.PULL_SIGNAL, 0);
    }

    @Test
    void signalDequeuedWhileDisabledIsDroppedForGoodAndFreshOnesFlowAfterReenable() throws InterruptedException {
        context.setSignalDispatchEnabled(false);
        startPump();
        context.sendSignal(TestContext.PULL_SIGNAL);
        context.sendSignal(0);
        // FIFO with a single consumer: once the 0 arrives, the PULL before it was dequeued and dropped.
        awaitSize(received, 1);
        assertThat(received).containsExactly(0);

        context.setSignalDispatchEnabled(true);
        // RELEASE rather than PULL: the dropped PULL still consumed the dedup slot.
        context.sendSignal(TestContext.RELEASE_SIGNAL);
        context.sendSignal(0);
        awaitSize(received, 3);
        assertThat(received).containsExactly(0, TestContext.RELEASE_SIGNAL, 0);
    }

    @Test
    void removedSignalListenerStopsReceivingWhileRemainingOnesStillDo() throws InterruptedException {
        List<Integer> removable = Collections.synchronizedList(new ArrayList<>());
        SignalListener listener = removable::add;
        context.addSignalListener(listener);
        startPump();

        context.sendSignal(TestContext.PULL_SIGNAL);
        awaitSize(removable, 1);

        context.removeSignalListener(listener);
        context.sendSignal(0);
        awaitSize(received, 2);

        assertThat(received).containsExactly(TestContext.PULL_SIGNAL, 0);
        assertThat(removable).containsExactly(TestContext.PULL_SIGNAL);
    }

    @ParameterizedTest
    @ValueSource(doubles = {Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY})
    void constructorRefusesNonFiniteUpperLimit(double limit) {
        assertThatThrownBy(() -> new TestContext(1L, limit, 0.0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(doubles = {Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY})
    void constructorRefusesNonFiniteLowerLimit(double limit) {
        assertThatThrownBy(() -> new TestContext(1L, 0.0, limit))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(doubles = {Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY})
    void upperLimitSetterRefusesNonFiniteValues(double limit) {
        assertThatThrownBy(() -> context.setUpperLimit(limit))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(context.getUpperLimit()).isEqualTo(100.0);
    }

    @ParameterizedTest
    @ValueSource(doubles = {Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY})
    void lowerLimitSetterRefusesNonFiniteValues(double limit) {
        assertThatThrownBy(() -> context.setLowerLimit(limit))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(context.getLowerLimit()).isEqualTo(-100.0);
    }
}
