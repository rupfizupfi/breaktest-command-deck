package ch.rupfizupfi.deck.device;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Reference-counted hardware connection: the first {@link #connect()} opens it, the last
 * {@link #disconnect()} closes it, so several subsystems can share one USB session without
 * coordinating. See {@code doc/03-backend/hardware-integration.md#the-device-base-class}.
 */
public abstract class Device {
    private static final Logger logger = LoggerFactory.getLogger(Device.class);

    private final AtomicInteger connectionCount = new AtomicInteger(0);
    private CompletableFuture<Boolean> connectionFuture = new CompletableFuture<>();

    /** True while reset() is rebuilding the connection, so a subclass can tell a reopen from a first open. */
    protected volatile boolean reopening = false;

    /**
     * The count is only raised once openConnection() has returned normally, so a failed open leaves
     * the device at zero references and the next connect() retries it.
     */
    public synchronized void connect() {
        if (connectionCount.get() == 0) {
            try {
                openConnection();
            } catch (Throwable t) {
                // Never complete the future on failure; a fresh incomplete one is installed so a
                // later successful connect() still ends up with a normally completed future.
                // Errors (e.g. UnsatisfiedLinkError from the USB native binding) roll back too.
                connectionFuture = new CompletableFuture<>();
                throw t;
            }
            connectionCount.set(1);
            connectionFuture.complete(true);
        } else {
            connectionCount.incrementAndGet();
        }
    }

    /**
     * Clamped at zero: unbalanced calls - test cleanup runs disconnect() even when setup() failed
     * before connect(), and may run twice - must not push the count negative, which would stop
     * openConnection() from ever being called again.
     */
    public synchronized void disconnect() {
        // All mutation is serialized by this monitor, so the read-then-decrement is safe.
        if (connectionCount.get() == 0) {
            logger.warn("disconnect() called on a device that is not connected, ignoring");
            return;
        }

        if (connectionCount.decrementAndGet() == 0) {
            // A failing close still releases the reference and resets the future,
            // so the device can be opened again instead of staying pinned.
            try {
                closeConnection();
            } finally {
                connectionFuture = new CompletableFuture<>();
            }
        }
    }

    /**
     * Rebuilds the hardware connection underneath the references that already exist: close, re-open,
     * same count.
     * <p>
     * The reference count is deliberately NOT touched, and that is the entire difference from
     * {@code disconnect(); connect();}. During a run the load cell is held by {@code LoadCellThread}
     * and possibly a second time by {@code DeviceService.enableInfoBroadcasting()}, so a
     * disconnect/connect pair would merely decrement to 1, never reach {@link #closeConnection()},
     * and the stopped stream would never be replaced. Every existing holder keeps its reference
     * across the reset and its eventual {@link #disconnect()} still balances.
     * <p>
     * A failing {@link #openConnection()} propagates: the count is preserved and the future stays
     * incomplete, so {@link #isConnected()} is false and the caller's next attempt calls reset()
     * again - the same rollback contract {@link #connect()} has.
     *
     * @throws IllegalStateException when nobody holds the device; resetting it would open a handle
     *                               with no owner left to close it
     */
    public synchronized void reset() {
        if (connectionCount.get() == 0) {
            throw new IllegalStateException("reset() on a device with no connections would open a "
                    + "hardware handle that no holder is left to close");
        }

        // Installed BEFORE the teardown, so isConnected() reports false for the whole cycle and no
        // other thread goes on believing in the handle that is about to be closed.
        connectionFuture = new CompletableFuture<>();
        reopening = true;
        try {
            try {
                closeConnection();
            } catch (Throwable t) {
                // Logged only: building a new connection is the entire point of a reset, and a handle
                // that will not close cleanly is exactly the condition that prompted one.
                logger.warn("Closing the device connection during reset() failed, re-opening anyway", t);
            }
            openConnection();
        } finally {
            reopening = false;
        }
        connectionFuture.complete(true);
    }

    /**
     * Drops the connection bookkeeping after the hardware handle was torn down out of band
     * (emergency stop re-enumeration), so the next connect() actually re-opens instead of
     * handing out a phantom connection. Reference holders that later call disconnect() hit
     * the zero clamp, which is harmless.
     * <p>
     * REQUIREMENT, not a description: only legal immediately after the caller has itself closed the
     * hardware handle. Nothing here closes anything, so calling it on a device that still holds a
     * live handle leaks that handle. To re-open a device whose handle is still open, use
     * {@link #reset()} - it closes first and keeps the references.
     */
    protected synchronized void markConnectionLost() {
        // closeConnection() is deliberately NOT called: the caller already tore the handle down.
        int lostReferences = connectionCount.getAndSet(0);
        // A fresh incomplete future stops isConnected() from reporting a connection that is gone.
        connectionFuture = new CompletableFuture<>();
        logger.warn("Device connection was lost out of band, dropping {} reference(s); "
                + "the next connect() will re-open the hardware", lostReferences);
    }

    public boolean isConnected() {
        return connectionFuture.isDone() && !connectionFuture.isCompletedExceptionally();
    }

    public CompletableFuture<Boolean> getConnectionStatus() {
        return connectionFuture;
    }

    protected abstract void openConnection();

    protected abstract void closeConnection();
}
