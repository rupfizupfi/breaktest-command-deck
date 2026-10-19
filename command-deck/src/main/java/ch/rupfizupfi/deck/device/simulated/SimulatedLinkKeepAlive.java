package ch.rupfizupfi.deck.device.simulated;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Supplier;

/**
 * One simulated handle's feed for the drive's link watchdog, as the CFW11 driver's keep-alive feeds
 * P0314 (usbmodbus {@code LinkKeepAlive}): once {@code enableLinkLossStop} armed it, real traffic
 * is the keep-alive, and one STATUS read is injected only when no transaction ended within a third
 * of the timeout. The deck sends no keep-alive of its own to a gated driver.
 * <p>
 * Nothing is injected while a transaction is in flight or an injected read is still outstanding, so
 * a wedged transaction sends nothing more and the watchdog fires, as it should. A de-energizing
 * transaction {@linkplain #deEnergizing() pauses} it until the next arm, which the deck sends at
 * every energize. After {@link #close()} it never injects again. Simpler than the driver's in one
 * respect: an injected read that waits behind a linked group still goes out, which costs the plant
 * one read and changes nothing it models.
 */
final class SimulatedLinkKeepAlive {

    /** The shortest re-check while busy: a third of P0314's 0.1 s step. */
    private static final long MIN_RECHECK_NANOS = TimeUnit.MILLISECONDS.toNanos(33);
    /** Parks between decisions are bounded, so a lost wake-up costs a second, never a hang. */
    private static final long MAX_PARK_NANOS = TimeUnit.SECONDS.toNanos(1);

    private final AtomicBoolean started = new AtomicBoolean();
    private final AtomicInteger inFlight = new AtomicInteger();
    private volatile String threadName = "simulated-drive-keepalive";
    private volatile Supplier<CompletableFuture<?>> inject;
    /** A third of the armed timeout; zero until armed. */
    private volatile long periodNanos;
    private volatile long lastEndedNanos = System.nanoTime();
    private volatile boolean paused;
    private volatile boolean closed;
    private volatile CompletableFuture<?> outstanding;
    private volatile Thread runner;

    /** Names the runner and gives it the read to inject; before the first arm. */
    void bind(String name, Supplier<CompletableFuture<?>> read) {
        this.threadName = Objects.requireNonNull(name);
        this.inject = Objects.requireNonNull(read);
    }

    /** On the I/O thread, once the drive holds {@code timeout}; ends a pause and wakes the runner. */
    void armed(Duration timeout) {
        periodNanos = timeout.toNanos() / 3;
        lastEndedNanos = System.nanoTime();
        paused = false;
        if (started.compareAndSet(false, true)) {
            Thread.ofVirtual().name(threadName).start(this::run);
        } else {
            wake();
        }
    }

    /** On the I/O thread, as a transaction starts. */
    void transactionStarted() {
        inFlight.incrementAndGet();
    }

    /** On the I/O thread, as a transaction ends, whatever its outcome. */
    void transactionEnded() {
        lastEndedNanos = System.nanoTime();
        inFlight.decrementAndGet();
    }

    /** On the I/O thread, as a transaction that clears start or general enable is sent. */
    void deEnergizing() {
        paused = true;
    }

    void close() {
        closed = true;
        wake();
    }

    /** True while the runner runs; a test's to watch end after {@link #close()}. */
    boolean running() {
        Thread thread = runner;
        return thread != null && thread.isAlive();
    }

    /** Whether the runner injects at all right now: armed, not paused, not closed. */
    boolean feeding() {
        return periodNanos > 0 && !paused && !closed;
    }

    private void wake() {
        Thread thread = runner;
        if (thread != null) {
            LockSupport.unpark(thread);
        }
    }

    /** @return nanoseconds until the next decision, or -1 once closed */
    long tick() {
        long period = periodNanos;
        if (closed) {
            return -1;
        }
        if (period == 0 || paused) {
            return MAX_PARK_NANOS;
        }
        long now = System.nanoTime();
        long due = lastEndedNanos + period;
        if (now - due < 0) {
            return due - now;
        }
        CompletableFuture<?> pending = outstanding;
        if ((pending != null && !pending.isDone()) || inFlight.get() > 0) {
            return Math.max(period / 4, MIN_RECHECK_NANOS);
        }
        try {
            outstanding = inject.get();
        } catch (IllegalStateException e) {
            // The handle closed under us.
            closed = true;
            return -1;
        }
        return period;
    }

    private void run() {
        runner = Thread.currentThread();
        while (true) {
            long wait = tick();
            if (wait < 0) {
                return;
            }
            LockSupport.parkNanos(this, Math.min(wait, MAX_PARK_NANOS));
        }
    }
}
