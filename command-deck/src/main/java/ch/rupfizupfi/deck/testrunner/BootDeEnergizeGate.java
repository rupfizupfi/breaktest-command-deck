package ch.rupfizupfi.deck.testrunner;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Holds every Start back while {@link StartupRecoveryRunner}'s boot de-energize is still pending,
 * so a run cannot energize the motor and then lose it to the de-energize mid-setup (H-13).
 * <p>
 * Static because there is one boot per process, and because {@link TestRunnerThread} is built with
 * {@code new} by {@code TestRunnerFactory}, outside the reach of injection. Open when nothing
 * pending: a simulated or de-energize-disabled boot never closes it.
 */
final class BootDeEnergizeGate {

    private static final AtomicReference<CountDownLatch> pending = new AtomicReference<>();

    private BootDeEnergizeGate() {
    }

    /** Closes the gate; the returned token reopens it through {@link #complete}. */
    static CountDownLatch begin() {
        CountDownLatch token = new CountDownLatch(1);
        pending.set(token);
        return token;
    }

    /** Idempotent. A stale token never reopens a gate a later {@link #begin} closed. */
    static void complete(CountDownLatch token) {
        token.countDown();
        pending.compareAndSet(token, null);
    }

    static boolean isPending() {
        CountDownLatch token = pending.get();
        return token != null && token.getCount() > 0;
    }

    /**
     * @return true once no de-energize is pending, false if one still is after {@code timeoutMs}
     */
    static boolean awaitReady(long timeoutMs) throws InterruptedException {
        CountDownLatch token = pending.get();
        return token == null || token.await(timeoutMs, TimeUnit.MILLISECONDS);
    }

    /** For tests: the gate is process-wide, so a test that closed it must leave it open. */
    static void resetForTests() {
        CountDownLatch token = pending.getAndSet(null);
        if (token != null) {
            token.countDown();
        }
    }
}
