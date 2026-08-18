package ch.rupfizupfi.deck.device.frequencyconverter;

import ch.rupfizupfi.deck.device.Device;
import ch.rupfizupfi.deck.device.api.Drive;
import ch.rupfizupfi.deck.device.api.DriveProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Single serialized gateway to the CFW11 USB/Modbus handle.
 * Every read and write of the drive goes through {@code driveLock}, so the info poll thread
 * and the test runner can no longer interleave requests on the wire.
 * <p>
 * Lock ordering rule: the {@link Device} instance monitor (held by the synchronized
 * {@code connect()} / {@code disconnect()}) may be taken before {@code driveLock}, NEVER the
 * reverse. So {@code connect()}, {@code disconnect()}, {@code markConnectionLost()},
 * {@link #tryStartThread()}, {@link #tryStopThread()} and {@link #dropConnectionBookkeeping()}
 * must never be called from inside a {@code driveLock} section, i.e. never from inside
 * {@link #runExclusive} / {@link #computeExclusive}. {@link #closeDriveHandle()} takes
 * {@code driveLock} only and touches no monitor, so it IS safe there.
 * <p>
 * Teardown is split into those two methods so the caller can order them itself: reset the
 * bookkeeping first (monitor, no {@code driveLock}), then hold {@code driveLock} across the
 * close plus the fresh re-enumeration. That makes teardown-and-reopen atomic against a
 * concurrent {@code connect()}, which then simply blocks on {@code driveLock}, instead of
 * slipping in between the halves and leaving two live USB handles on one drive.
 */
public class CFW11Device extends Device {
    private static final Logger logger = LoggerFactory.getLogger(CFW11Device.class);

    /** Bounded so a wedged native USB call can never pin the instance monitor forever. */
    private static final long POLL_THREAD_JOIN_TIMEOUT_MS = 2000;

    private final ReentrantLock driveLock = new ReentrantLock();
    private final DriveProvider driveProvider;
    private volatile Drive drive;
    private final List<InfoObserver> observers = new CopyOnWriteArrayList<>();
    private Thread dataThread;
    private volatile boolean isRunning = false;
    private int idProvider = 0;
    /**
     * Throttles the "handle gone" logging to one line per outage. Volatile because an abandoned
     * poll thread can briefly overlap its replacement; a lost update only costs a log line.
     */
    private volatile boolean driveWasUnavailable = false;

    public CFW11Device(DriveProvider driveProvider) {
        this.driveProvider = driveProvider;
    }

    @Override
    protected void openConnection() {
        driveLock.lock();
        try {
            drive = driveProvider.open();
        } finally {
            driveLock.unlock();
        }

        // Outside the drive lock: tryStartThread() is synchronized on the instance monitor.
        tryStartThread();
    }

    protected synchronized void tryStartThread() {
        if (dataThread == null && !observers.isEmpty()) {
            isRunning = true;
            dataThread = new Thread(this::readData);
            dataThread.start();
        }
    }

    protected synchronized void tryStopThread() {
        // isRunning is cleared only when we actually intend to stop, i.e. together with nulling
        // dataThread. Clearing it unconditionally killed the poll loop whenever closeConnection()
        // ran with observers still registered, while the guard below left dataThread non-null - so
        // tryStartThread()'s null check then refused to ever start polling again and the dashboard
        // froze on its last Info with no way back short of a restart.
        if (dataThread != null && observers.isEmpty()) {
            isRunning = false;
            // Interrupt first: the loop breaks out of its 400 ms sleep at once instead of being
            // waited on for the rest of the tick.
            dataThread.interrupt();
            try {
                // Bounded: the poll thread's exit path goes through driveLock, which the safe-stop
                // escalation can hold across uninterruptible native USB calls. An unbounded join
                // would pin this instance monitor and with it every connect()/disconnect().
                dataThread.join(POLL_THREAD_JOIN_TIMEOUT_MS);
                if (dataThread.isAlive()) {
                    logger.warn("CFW11 poll thread did not stop within {} ms, abandoning it",
                            POLL_THREAD_JOIN_TIMEOUT_MS);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            // Cleared even when the join timed out: isRunning is already false, so the stranded
            // thread exits after its current iteration. Keeping the reference would make
            // tryStartThread()'s null guard refuse to ever start a poll thread again, which is
            // worse than a brief overlap of two poll threads.
            dataThread = null;
        }
    }

    @Override
    protected void closeConnection() {
        // Must run BEFORE the drive lock is taken: tryStopThread() joins the poll thread, which
        // may itself be waiting for driveLock. Holding the lock across that join would deadlock.
        tryStopThread();

        driveLock.lock();
        try {
            if (drive != null) {
                drive.close();
                drive = null;
            }
        } finally {
            driveLock.unlock();
        }
    }

    /** True when a drive handle is currently open. */
    public boolean isDriveHandleOpen() {
        return drive != null;
    }

    /**
     * Runs action against the drive holding the exclusive drive lock.
     * Throws DriveUnavailableException if no handle is open.
     */
    public void withDrive(Consumer<Drive> action) {
        driveLock.lock();
        try {
            var handle = drive;
            if (handle == null) {
                throw new DriveUnavailableException("CFW11 drive handle is not open");
            }
            action.accept(handle);
        } finally {
            driveLock.unlock();
        }
    }

    /** Same, for actions that return a value. */
    public <T> T queryDrive(Function<Drive, T> action) {
        driveLock.lock();
        try {
            var handle = drive;
            if (handle == null) {
                throw new DriveUnavailableException("CFW11 drive handle is not open");
            }
            return action.apply(handle);
        } finally {
            driveLock.unlock();
        }
    }

    /**
     * Holds the exclusive drive lock without requiring an open handle.
     * Used by the safe-stop escalation, which brings its own fresh drive handle.
     */
    public void runExclusive(Runnable action) {
        driveLock.lock();
        try {
            action.run();
        } finally {
            driveLock.unlock();
        }
    }

    /** Same, returning a value. */
    public <T> T computeExclusive(Supplier<T> action) {
        driveLock.lock();
        try {
            return action.get();
        } finally {
            driveLock.unlock();
        }
    }

    /**
     * Closes and forgets the current handle. Takes {@code driveLock} ONLY and never touches the
     * instance monitor, so it is safe to call from inside {@link #runExclusive} - which is how the
     * safe-stop escalation keeps close plus re-enumeration atomic against a concurrent
     * {@code connect()}. Never throws. Safe when no handle is open.
     */
    public void closeDriveHandle() {
        driveLock.lock();
        try {
            if (drive != null) {
                // Best effort: a drive that is already wedged or unplugged may fail to close, but
                // the escalation must still get to drop the reference and re-enumerate.
                try {
                    drive.close();
                } catch (Throwable t) {
                    logger.warn("Failed to close CFW11 USB communication while invalidating the handle", t);
                }
            }
            // Unconditional, even when the close threw: keeping a reference to a handle we can no
            // longer trust is worse than losing it - withDrive/queryDrive would keep using it.
            drive = null;
        } finally {
            driveLock.unlock();
        }
    }

    /**
     * Resets the {@link Device} reference bookkeeping so the next {@code connect()} really re-opens
     * the hardware instead of handing out a phantom connection. Takes the instance monitor, so it
     * must NOT be called while holding {@code driveLock} - see the lock ordering rule on the class.
     * Never throws.
     */
    public void dropConnectionBookkeeping() {
        try {
            markConnectionLost();
        } catch (Throwable t) {
            // Swallowed on purpose: this runs on the emergency-stop path, where losing the
            // bookkeeping reset must never abort the teardown that follows it.
            logger.warn("Failed to drop CFW11 connection bookkeeping", t);
        }
    }

    public void registerObserver(InfoObserver observer) {
        observers.add(observer);
        tryStartThread();
    }

    public void unregisterObserver(InfoObserver observer) {
        observers.remove(observer);
        tryStopThread();
    }

    private void notifyObservers(Info info) {
        for (InfoObserver observer : observers) {
            observer.update(info);
        }
    }

    private void readData() {
        while (isRunning) {
            try {
                // Both maps are fetched in one locked section so they form a consistent pair;
                // the lock is released again before notifying observers and before sleeping,
                // otherwise the 400 ms cadence would starve the safety stop.
                var snapshot = queryDrive(drive -> new DriveSnapshot(drive.getMotorData(), drive.getControlParameters()));
                var motorData = snapshot.motorData();
                var controlParameters = snapshot.controlParameters();

                var info = new Info();
                info.start = controlParameters.get("start");
                info.generalEnable = controlParameters.get("generalEnable");
                info.useSecondRamp = controlParameters.get("useSecondRamp");
                info.directionIsForward = controlParameters.get("directionIsForward");
                info.speed = motorData.get("speed");
                info.motorCurrent = motorData.get("current");
                info.motorVoltage = motorData.get("voltage");
                info.motorTorque = motorData.get("torque");
                info.id = idProvider++;
                this.notifyObservers(info);
                if (driveWasUnavailable) {
                    driveWasUnavailable = false;
                    logger.info("CFW11 drive handle is available again, resuming info polling");
                }
            } catch (DriveUnavailableException e) {
                // Handle closed (typically after a safe-stop escalation) - skip this round. Logged
                // once per outage: observers keep showing the last Info, so silence would hide a
                // permanently dead poll behind stale dashboard values.
                if (!driveWasUnavailable) {
                    driveWasUnavailable = true;
                    logger.warn("CFW11 drive handle is not open, info polling is idle until it reopens");
                }
            } catch (RuntimeException e) {
                logger.warn("Failed to poll CFW11 device data", e);
            }

            try {
                Thread.sleep(400);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    private record DriveSnapshot(Map<String, Integer> motorData, Map<String, Boolean> controlParameters) {
    }
}
