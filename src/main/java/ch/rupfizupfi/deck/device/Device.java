package ch.rupfizupfi.deck.device;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Represents a physical device with a communication connection to the hardware.
 * The first connection creates the hardware connection and starts data fetching.
 * The device maintains the connection while connected and closes it when the last connection is closed.
 * It holds data to avoid repeated requests from the hardware.
 * Provides an instance of the hardware component for use by connecting classes.
 * Abstract methods are provided for getting information from the hardware component.
 * The connector should know when the connection is established.
 */
public abstract class Device {
    private final AtomicInteger connectionCount = new AtomicInteger(0);
    private CompletableFuture<Boolean> connectionFuture = new CompletableFuture<>();

    // Method to connect to the device
    public synchronized void connect() {
        if (connectionCount.incrementAndGet() == 1) {
            openConnection();
            connectionFuture.complete(true);
        }
    }

    // Method to disconnect from the device
    public synchronized void disconnect() {
        if (connectionCount.decrementAndGet() == 0) {
            closeConnection();
            connectionFuture = new CompletableFuture<>();
        }
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

    public abstract Object getHardwareComponent();
}
