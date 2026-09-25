package ch.rupfizupfi.deck.device.frequencyinverter;

import ch.rupfizupfi.deck.device.Device;
import ch.rupfizupfi.deck.device.api.Drive;
import ch.rupfizupfi.deck.device.api.DriveProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Single serialized gateway to the drive handle of the frequency inverter.
 * Every read and write of the drive goes through {@code driveLock}, so the info poll thread
 * and the test runner can no longer interleave requests on the wire.
 * <p>
 * Drive-model agnostic: every hardware call goes through {@link Drive}, and which inverter
 * answers is the injected {@link DriveProvider}'s decision. No vendor type, register number or
 * model name belongs in this class - a second inverter model is a new provider, not an edit here.
 * <p>
 * Lock ordering rule: the {@link Device} instance monitor (held by the synchronized
 * {@code connect()} / {@code disconnect()}) may be taken before {@code driveLock}, NEVER the
 * reverse. So {@code connect()}, {@code disconnect()}, {@code markConnectionLost()},
 * {@link #tryStartThread()}, {@link #tryStopThread()} and {@link #dropConnectionBookkeeping()}
 * must never be called from inside a {@code driveLock} section, i.e. never from inside
 * {@link #runExclusive}. {@link #closeDriveHandle()} takes {@code driveLock} only and
 * touches no monitor, so it IS safe there.
 * <p>
 * Teardown is split into those two methods so the caller can order them itself: reset the
 * bookkeeping first (monitor, no {@code driveLock}), then hold {@code driveLock} across the
 * close plus the fresh re-enumeration. That makes teardown-and-reopen atomic against a
 * concurrent {@code connect()}, which then simply blocks on {@code driveLock}, instead of
 * slipping in between the halves and leaving two live USB handles on one drive.
 */
public class FrequencyInverterDevice extends Device {
    private static final Logger logger = LoggerFactory.getLogger(FrequencyInverterDevice.class);

    /** Bounded so a wedged native USB call can never pin the instance monitor forever. */
    private static final long POLL_THREAD_JOIN_TIMEOUT_MS = 2000;

    private final ReentrantLock driveLock = new ReentrantLock();
    private final DriveProvider driveProvider;
    private volatile Drive drive;
    private final List<InfoObserver> observers = new CopyOnWriteArrayList<>();
    private Thread dataThread;
    /** The run flag of {@code dataThread}, guarded by the instance monitor together with it. */
    private AtomicBoolean pollRunning;
    /** Atomic because an abandoned poll thread briefly overlaps its replacement: frame ids stay unique. */
    private final AtomicInteger idProvider = new AtomicInteger();
    /**
     * Throttles the "handle gone" logging to one line per outage. Volatile because an abandoned
     * poll thread can briefly overlap its replacement; a lost update only costs a log line.
     */
    private volatile boolean driveWasUnavailable = false;
    /** Throttles poll-failure logging to one line per outage, volatile for the same reason. */
    private volatile boolean pollWasFailing = false;
    /** Throttles observer-failure logging to one line per outage, volatile for the same reason. */
    private volatile boolean observerWasFailing = false;

    public FrequencyInverterDevice(DriveProvider driveProvider) {
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
            var running = new AtomicBoolean(true);
            pollRunning = running;
            dataThread = new Thread(() -> readData(running), "frequency-inverter-poll");
            dataThread.start();
        }
    }

    protected synchronized void tryStopThread() {
        if (dataThread != null && observers.isEmpty()) {
            // Each poll thread exits on its own flag, so a thread abandoned by the bounded join
            // below stays stopped once a replacement starts and publishes no frame after it.
            pollRunning.set(false);
            // Interrupt first: the loop breaks out of its 400 ms sleep at once instead of being
            // waited on for the rest of the tick.
            dataThread.interrupt();
            try {
                // Bounded: the poll thread's exit path goes through driveLock, which the safe-stop
                // escalation can hold across uninterruptible native USB calls. An unbounded join
                // would pin this instance monitor and with it every connect()/disconnect().
                dataThread.join(POLL_THREAD_JOIN_TIMEOUT_MS);
                if (dataThread.isAlive()) {
                    logger.warn("Frequency inverter poll thread did not stop within {} ms, abandoning it",
                            POLL_THREAD_JOIN_TIMEOUT_MS);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            // Nulled even after a timed-out join, so tryStartThread()'s null guard can start a
            // replacement.
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
            var handle = drive;
            drive = null;
            if (handle != null) {
                // Dropped even when the close fails: a handle we cannot close is not one
                // withDrive/queryDrive may keep using, nor one connect() may open a second time.
                try {
                    handle.close();
                } catch (Throwable t) {
                    logger.warn("Failed to close the drive handle on disconnect", t);
                }
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
                throw new DriveUnavailableException("Frequency inverter drive handle is not open");
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
                throw new DriveUnavailableException("Frequency inverter drive handle is not open");
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
                    logger.warn("Failed to close the drive handle while invalidating it", t);
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
            logger.warn("Failed to drop the frequency inverter connection bookkeeping", t);
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

    /**
     * An observer failure is the observer's, never the drive's: the other observers still get this
     * tick and the poll outage flags stay untouched.
     */
    private void notifyObservers(Info info) {
        boolean failed = false;
        for (InfoObserver observer : observers) {
            try {
                observer.update(info);
            } catch (RuntimeException e) {
                failed = true;
                // Stack trace once per outage, message only afterwards, so a permanently broken
                // observer cannot flood the log at the poll cadence.
                if (!observerWasFailing) {
                    observerWasFailing = true;
                    logger.warn("Frequency inverter info observer {} failed, skipping it until it recovers",
                            observer.getClass().getName(), e);
                } else {
                    logger.debug("Frequency inverter info observer {} failed: {}",
                            observer.getClass().getName(), e.getMessage());
                }
            }
        }
        if (!failed && observerWasFailing) {
            observerWasFailing = false;
            logger.info("Frequency inverter info observers accepted a full round again");
        }
    }

    /**
     * Builds the frame the observers see. Every key is required: a map missing one is a poll
     * failure naming it, never a zero reading.
     */
    static Info toInfo(Map<String, Integer> motorData, Map<String, Boolean> controlParameters, int id) {
        var info = new Info();
        info.start = require(controlParameters, "control parameters", "start");
        info.generalEnable = require(controlParameters, "control parameters", "generalEnable");
        info.useSecondRamp = require(controlParameters, "control parameters", "useSecondRamp");
        info.directionIsForward = require(controlParameters, "control parameters", "directionIsForward");
        info.speed = require(motorData, "motor data", "speed");
        info.motorCurrent = require(motorData, "motor data", "current");
        info.motorVoltage = require(motorData, "motor data", "voltage");
        info.motorTorque = require(motorData, "motor data", "torque");
        info.id = id;
        return info;
    }

    private static <T> T require(Map<String, T> map, String source, String key) {
        var value = map.get(key);
        if (value == null) {
            throw new IllegalStateException("drive %s has no key '%s'".formatted(source, key));
        }
        return value;
    }

    private void readData(AtomicBoolean running) {
        while (running.get()) {
            Info info = null;
            try {
                // Both maps are fetched in one locked section so they form a consistent pair;
                // the lock is released again before notifying observers and before sleeping,
                // otherwise the 400 ms cadence would starve the safety stop.
                var snapshot = queryDrive(drive -> new DriveSnapshot(drive.getMotorData(), drive.getControlParameters()));

                info = toInfo(snapshot.motorData(), snapshot.controlParameters(), idProvider.getAndIncrement());
                if (driveWasUnavailable) {
                    driveWasUnavailable = false;
                    logger.info("Drive handle is available again, resuming info polling");
                }
                if (pollWasFailing) {
                    pollWasFailing = false;
                    logger.info("Frequency inverter answered again, resuming info polling");
                }
            } catch (DriveUnavailableException e) {
                // Handle closed (typically after a safe-stop escalation) - skip this round. Logged
                // once per outage: observers keep showing the last Info, so silence would hide a
                // permanently dead poll behind stale dashboard values.
                if (!driveWasUnavailable) {
                    driveWasUnavailable = true;
                    logger.warn("Drive handle is not open, info polling is idle until it reopens");
                }
            } catch (Exception e) {
                // The driver's checked comms exception crosses Drive undeclared, so Exception - not
                // RuntimeException - is the poll boundary that keeps this thread alive. Stack trace
                // once per outage, message only afterwards, so a dead link cannot flood the log.
                if (!pollWasFailing) {
                    pollWasFailing = true;
                    logger.warn("Failed to poll frequency inverter data, info polling is idle until it answers", e);
                } else {
                    logger.debug("Failed to poll frequency inverter data: {}", e.getMessage());
                }
            }

            // Outside the try: only a produced Info is delivered, and an observer's own failure
            // is never read as a drive outage. A thread stopped while it sat in the drive call
            // keeps the frame it came back with.
            if (info != null && running.get()) {
                notifyObservers(info);
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
