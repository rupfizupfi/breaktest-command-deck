package ch.rupfizupfi.deck.device;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Reference-counted hardware connection: the first {@link #connect()} opens it, the last
 * {@link #disconnect()} closes it, so several subsystems can share one USB session without
 * coordinating. See {@code doc/dev/backend/hardware-integration.md#the-device-base-class}.
 * <p>
 * Two locks guard the lifecycle. The instance monitor guards the state; the lifecycle lock is
 * taken outside it by {@link #connect()}, {@link #disconnect()} and {@link #reset()}, which
 * can hold it as long as an open or close takes, so a caller that must not wait without a bound -
 * the safe stop's tier 2 - can give up on it ({@link #tryLockLifecycle}) where a monitor cannot be
 * given up on. The order is always lifecycle lock, then monitor: a thread holding the monitor never
 * takes the lifecycle lock, or it deadlocks against a {@code connect()}.
 */
public abstract class Device {
    private static final Logger logger = LoggerFactory.getLogger(Device.class);

    private final ReentrantLock lifecycle = new ReentrantLock();
    /** Who holds {@link #lifecycle}, recorded by its outermost acquire, for a waiter that gives up. */
    private volatile LifecycleHolder lifecycleHolder;

    private final AtomicInteger connectionCount = new AtomicInteger(0);
    private CompletableFuture<Boolean> connectionFuture = new CompletableFuture<>();

    /**
     * Set by {@link #markConnectionLost()}: the handle is gone but its holders still count, so the
     * next connect() re-opens. Guarded by the instance monitor.
     */
    private boolean handleLost = false;

    /** True while reset() is rebuilding the connection, so a subclass can tell a reopen from a first open. */
    protected volatile boolean reopening = false;

    /**
     * The count is only raised once openConnection() has returned normally, so a failed open leaves
     * the count where it was (zero, or the holders of a lost handle) and the next connect() retries it.
     */
    public void connect() {
        lockLifecycle("connect()");
        try {
            openOrCount();
        } finally {
            unlockLifecycle();
        }
    }

    private synchronized void openOrCount() {
        if (connectionCount.get() == 0 || handleLost) {
            try {
                openConnection();
            } catch (Throwable t) {
                // Errors (e.g. UnsatisfiedLinkError from the native binding) roll back too.
                connectionFuture = new CompletableFuture<>();
                throw t;
            }
            handleLost = false;
            connectionCount.incrementAndGet();
            connectionFuture.complete(true);
        } else {
            connectionCount.incrementAndGet();
        }
    }

    /**
     * Clamped at zero, because test cleanup can call disconnect() without a matching connect().
     */
    public void disconnect() {
        lockLifecycle("disconnect()");
        try {
            releaseOrClose();
        } finally {
            unlockLifecycle();
        }
    }

    private synchronized void releaseOrClose() {
        if (connectionCount.get() == 0) {
            logger.warn("disconnect() called on a device that is not connected, ignoring");
            return;
        }

        if (connectionCount.decrementAndGet() == 0) {
            // A failing close still resets the future, so the device can be opened again.
            try {
                closeConnection();
            } finally {
                connectionFuture = new CompletableFuture<>();
                handleLost = false;
            }
        }
    }

    /**
     * Rebuilds the hardware connection underneath the references that already exist: close, re-open,
     * same count.
     * <p>
     * Unlike {@code disconnect(); connect();}, which would stop at a count of 1 with a second holder
     * and never reach {@link #closeConnection()}, the reference count is untouched and every holder's
     * {@link #disconnect()} still balances.
     * <p>
     * A failing {@link #openConnection()} propagates with the count preserved and the future
     * incomplete, so {@link #isConnected()} is false and the caller may reset() again.
     *
     * @throws IllegalStateException when nobody holds the device
     */
    public void reset() {
        lockLifecycle("reset()");
        try {
            rebuild();
        } finally {
            unlockLifecycle();
        }
    }

    private synchronized void rebuild() {
        if (connectionCount.get() == 0) {
            throw new IllegalStateException("reset() on a device with no connections would open a "
                    + "hardware handle that no holder is left to close");
        }

        // Before the teardown, so isConnected() is false for the whole cycle.
        connectionFuture = new CompletableFuture<>();
        reopening = true;
        try {
            try {
                closeConnection();
            } catch (Throwable t) {
                // Logged only: a handle that will not close is what prompts a reset.
                logger.warn("Closing the device connection during reset() failed, re-opening anyway", t);
            }
            openConnection();
        } finally {
            reopening = false;
        }
        handleLost = false;
        connectionFuture.complete(true);
    }

    /**
     * Records that the hardware handle was torn down out of band (emergency stop re-enumeration), so
     * the next connect() re-opens instead of handing out a phantom connection.
     * <p>
     * The holders keep their references (OQ-68), so a holder from before the loss can never disconnect
     * a session a later connect() re-opened. The last disconnect still calls
     * {@link #closeConnection()}, which must therefore tolerate a handle that is already gone.
     * <p>
     * Only for a subclass whose {@link #openConnection()} closes a handle still open before opening
     * another, since the mark may be set over a live one. Use {@link #reset()} to re-open a device
     * whose handle is still open.
     */
    protected synchronized void markConnectionLost() {
        handleLost = true;
        connectionFuture = new CompletableFuture<>();
        logger.warn("Device connection was lost out of band; its {} holder(s) keep their reference "
                + "and the next connect() re-opens the hardware", connectionCount.get());
    }

    /** Waits for the lifecycle lock without a bound, as the monitor always did. */
    protected void lockLifecycle(String operation) {
        lifecycle.lock();
        noteHolder(operation);
    }

    /**
     * Waits at most {@code waitMs} for the lifecycle lock. Deaf to interrupts until then - a stop
     * thread is routinely interrupted by an operator Stop and must not lose its turn to one - and
     * restores the interrupt flag afterwards.
     *
     * @return true if the lock is now held; the caller then owes {@link #unlockLifecycle()}
     */
    protected boolean tryLockLifecycle(String operation, long waitMs) {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(waitMs);
        boolean interrupted = false;
        try {
            while (true) {
                try {
                    if (lifecycle.tryLock(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS)) {
                        noteHolder(operation);
                        return true;
                    }
                    return false;
                } catch (InterruptedException e) {
                    interrupted = true;
                }
            }
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    protected void unlockLifecycle() {
        if (lifecycle.getHoldCount() == 1) {
            lifecycleHolder = null;
        }
        lifecycle.unlock();
    }

    /** "connect() on thread 'x', held for N ms", or null when nobody holds the lifecycle lock. */
    protected String describeLifecycleHolder() {
        var holder = lifecycleHolder;
        if (holder == null) {
            return null;
        }
        return "%s on thread '%s', held for %d ms".formatted(holder.operation(), holder.thread(),
                TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - holder.sinceNanos()));
    }

    private void noteHolder(String operation) {
        if (lifecycle.getHoldCount() == 1) {
            lifecycleHolder = new LifecycleHolder(operation, Thread.currentThread().getName(), System.nanoTime());
        }
    }

    public boolean isConnected() {
        return connectionFuture.isDone() && !connectionFuture.isCompletedExceptionally();
    }

    public CompletableFuture<Boolean> getConnectionStatus() {
        return connectionFuture;
    }

    protected abstract void openConnection();

    protected abstract void closeConnection();

    private record LifecycleHolder(String operation, String thread, long sinceNanos) {
    }

    /** A bounded wait for the lifecycle lock ran out; nothing was done under it. */
    public static class LifecycleLockTimeoutException extends RuntimeException {
        public LifecycleLockTimeoutException(String message) {
            super(message);
        }
    }
}
