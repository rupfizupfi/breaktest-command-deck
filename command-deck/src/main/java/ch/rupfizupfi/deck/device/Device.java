package ch.rupfizupfi.deck.device;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Represents a physical device with a communication connection to the hardware.
 * The first connection creates the hardware connection and starts data fetching.
 * The device maintains the connection while connected and closes it when the last connection is closed.
 * Provides an instance of the hardware component for use by connecting classes.
 * Abstract methods are provided for getting information from the hardware component.
 * The connector should know when the connection is established.
 */
public abstract class Device {
    private static final Logger logger = LoggerFactory.getLogger(Device.class);

    private final AtomicInteger connectionCount = new AtomicInteger(0);
    private CompletableFuture<Boolean> connectionFuture = new CompletableFuture<>();

    // Method to connect to the device
    // The count is only raised once openConnection() has returned normally, so a failed
    // open leaves the device at zero references and the next connect() retries the open.
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

    // Method to disconnect from the device
    // Clamped at zero: unbalanced calls (test cleanup runs disconnect() even when setup() failed
    // before connect(), and may run twice) must not push the count negative, which would stop
    // openConnection() from ever being called again.
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
     * Drops the connection bookkeeping after the hardware handle was torn down out of band
     * (emergency stop re-enumeration), so the next connect() actually re-opens instead of
     * handing out a phantom connection. Reference holders that later call disconnect() hit
     * the zero clamp, which is harmless.
     */
    protected synchronized void markConnectionLost() {
        // closeConnection() is deliberately NOT called: the caller already tore the handle down.
        int lostReferences = connectionCount.getAndSet(0);
        // A fresh incomplete future stops isConnected() from reporting a connection that is gone.
        connectionFuture = new CompletableFuture<>();
        logger.warn("Device connection was lost out of band, dropping {} reference(s); "
                + "the next connect() will re-open the hardware", lostReferences);
    }

    // Check if the device is connected
    public boolean isConnected() {
        return connectionFuture.isDone() && !connectionFuture.isCompletedExceptionally();
    }

    public CompletableFuture<Boolean> getConnectionStatus() {
        return connectionFuture;
    }

    // Abstract methods to be implemented by subclasses
    protected abstract void openConnection();

    protected abstract void closeConnection();
}
