package ch.rupfizupfi.deck.device.frequencyinverter;

import ch.rupfizupfi.deck.device.Device;
import ch.rupfizupfi.deck.device.api.Drive;
import ch.rupfizupfi.deck.device.api.DriveClosePendingException;
import ch.rupfizupfi.deck.device.api.DriveCommunicationException;
import ch.rupfizupfi.deck.device.api.DriveGating;
import ch.rupfizupfi.deck.device.api.DriveLane;
import ch.rupfizupfi.deck.device.api.DriveProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * The deck's one gateway to the frequency inverter's drive handle: it opens and closes that handle
 * and polls it for the dashboard. Drive-model agnostic: no vendor type, register number or model name
 * belongs here; a second inverter model is a new {@link DriveProvider}.
 * <p>
 * It serializes no drive call. The driver does, since contract 1.5.0: a
 * {@link DriveGating#DRIVER_SERIALIZES} handle takes calls from any thread and dispatches a stop
 * ahead of everything but the one transaction already in flight, so {@link #withDrive} and
 * {@link #queryDrive} hand the handle straight to their action and a slow poll never delays a stop
 * here. A plugin that still answers {@link DriveGating#CALLER_SERIALIZES} gets no lock either:
 * {@code MotorSafetyController} refuses every non-stop write through it, so all it ever carries is
 * idempotent stops and reads - unserialized beside the poll, and with no deadline.
 * <p>
 * The handle's lifecycle - {@code drive}, {@link #pendingCloses}, opening and closing - is guarded
 * by the {@link Device} instance monitor, which {@code connect()}, {@code disconnect()} and
 * {@code reset()} hold, inside the {@link Device} lifecycle lock, around {@link #openConnection} and
 * {@link #closeConnection}. Holding them blocks no drive call: the accessors read the volatile
 * {@code drive} field without either. The safe stop's tier 2 holds both across close, fresh open,
 * stop and close ({@link #runExclusive}), and {@link #invalidateConnection} closes and marks the
 * handle lost in one section of the monitor, so no {@code connect()} lands in between.
 * {@link #openConnection} never opens over a live handle, nor while a handle whose close is still
 * pending holds the port. Together: at most one session on the drive at any time (OQ-50).
 */
public class FrequencyInverterDevice extends Device {
    private static final Logger logger = LoggerFactory.getLogger(FrequencyInverterDevice.class);

    /**
     * Bounded because the poll thread may be inside a drive call - on a gated handle until its STATUS
     * deadline, on an ungated one without any bound - and the join holds the lifecycle lock and the
     * instance monitor.
     */
    private static final long POLL_THREAD_JOIN_TIMEOUT_MS = 2000;

    /**
     * How long {@link #runExclusive} waits for the lifecycle lock: the contract's close bound plus
     * 1000 ms, enough for a {@code disconnect()} whose close pays that whole bound, and short enough
     * that a safe stop whose tier 1 failed at once reports tier 3 within 3000 ms.
     */
    public static final long RUN_EXCLUSIVE_WAIT_MS = DriveLane.STOP_DEADLINE_MILLIS + 1000;

    private final DriveProvider driveProvider;
    /** Written under the instance monitor; read without it by every drive call. */
    private volatile Drive drive;
    /**
     * Dropped handles whose {@code close()} gave up at the contract's bound with a transfer still in
     * flight. The contract closes each one's port no later than a later {@code close()} that
     * returns normally - only a provider whose I/O thread closes the port does it sooner, by itself -
     * so every connect and disconnect retries them, and no handle opens while one still answers
     * pending. Guarded by the instance monitor; at most one in practice, since nothing opens while
     * one is here.
     */
    private final List<Drive> pendingCloses = new ArrayList<>();
    private final List<InfoObserver> observers = new CopyOnWriteArrayList<>();
    private Thread dataThread;
    /** The run flag of {@code dataThread}, guarded by the instance monitor together with it. */
    private AtomicBoolean pollRunning;
    /** Atomic because an abandoned poll thread briefly overlaps its replacement: frame ids stay unique. */
    private final AtomicInteger idProvider = new AtomicInteger();
    /**
     * Throttles "handle gone" logging to one line per outage. Volatile because an abandoned poll
     * thread can overlap its replacement; a lost update only costs a log line.
     */
    private volatile boolean driveWasUnavailable = false;
    /** Throttles poll-failure logging to one line per outage, volatile for the same reason. */
    private volatile boolean pollWasFailing = false;
    /** Throttles observer-failure logging to one line per outage, volatile for the same reason. */
    private volatile boolean observerWasFailing = false;

    public FrequencyInverterDevice(DriveProvider driveProvider) {
        this.driveProvider = driveProvider;
    }

    /** How {@link #closeDriveHandle()} left the handle it dropped. */
    public enum HandleClose {
        /** There was no handle. */
        NONE,
        /** Its port is closed. */
        CLOSED,
        /**
         * Its close gave up with a transfer in flight ({@link DriveClosePendingException}): the port
         * is still held, so no fresh handle may open yet.
         */
        PENDING,
        /** Its close threw something else; best effort, the handle is dropped all the same. */
        FAILED
    }

    /**
     * Never stacks a second session on the drive: a handle still open here - one a
     * {@link #markHandleLost} left behind - is closed before the new one opens. One physical drive,
     * one USB session (OQ-50).
     * <p>
     * The one retry point for a pending close: it calls that handle's {@code close()} again, which
     * waits at most the contract's 1500 ms bound.
     *
     * @throws DriveUnavailableException while a dropped handle's transfer still holds the port; the
     *                                   connect fails and the next one retries
     */
    @Override
    protected synchronized void openConnection() {
        refuseWhilePending(retryPendingCloses("the previous drive handle, whose close was pending"));
        var stale = drive;
        if (stale != null) {
            drive = null;
            logger.warn("Opening the drive while an earlier handle is still open; closing that "
                    + "one first so only one USB session exists");
            refuseWhilePending(close(stale, "the stale drive handle before re-opening"));
        }
        drive = driveProvider.open();
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
            // Each poll thread has its own flag, so one abandoned by the bounded join stays stopped.
            pollRunning.set(false);
            // Interrupt first so the loop leaves its 400 ms sleep at once.
            dataThread.interrupt();
            try {
                dataThread.join(POLL_THREAD_JOIN_TIMEOUT_MS);
                if (dataThread.isAlive()) {
                    logger.warn("Frequency inverter poll thread did not stop within {} ms, abandoning it",
                            POLL_THREAD_JOIN_TIMEOUT_MS);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            // Nulled even after a timed-out join, so a replacement can start.
            dataThread = null;
        }
    }

    @Override
    protected synchronized void closeConnection() {
        tryStopThread();
        retryPendingCloses("the previous drive handle, whose close was pending, on disconnect");
        var handle = drive;
        drive = null;
        if (handle != null) {
            // Dropped even when the close fails.
            close(handle, "the drive handle on disconnect");
        }
    }

    /** Under the instance monitor: PENDING if any parked handle still holds the port. */
    private HandleClose retryPendingCloses(String what) {
        List<Drive> held = List.copyOf(pendingCloses);
        pendingCloses.clear();
        HandleClose worst = HandleClose.NONE;
        for (Drive handle : held) {
            if (close(handle, what) == HandleClose.PENDING) {
                worst = HandleClose.PENDING;
            }
        }
        return worst;
    }

    /**
     * Closes {@code handle}, which the caller already dropped, under the instance monitor. A pending
     * close parks it in {@link #pendingCloses}; any other failure is logged and the handle forgotten.
     */
    private HandleClose close(Drive handle, String what) {
        try {
            handle.close();
            return HandleClose.CLOSED;
        } catch (DriveClosePendingException e) {
            pendingCloses.add(handle);
            logger.warn("Could not finish closing {}, its port is still held: {}", what, e.getMessage());
            return HandleClose.PENDING;
        } catch (Throwable t) {
            logger.warn("Failed to close {}", what, t);
            return HandleClose.FAILED;
        }
    }

    private static void refuseWhilePending(HandleClose closed) {
        if (closed == HandleClose.PENDING) {
            throw new DriveUnavailableException("Frequency inverter not re-opened: the previous drive"
                    + " handle's transfer is still in flight and holds the port, and no second session"
                    + " is opened beside it (OQ-50); the next connect retries");
        }
    }

    public boolean isDriveHandleOpen() {
        return drive != null;
    }

    /**
     * Runs {@code action} against the open handle on the calling thread, straight through: the
     * driver gates the call, nothing here waits.
     *
     * @throws DriveUnavailableException if no handle is open
     */
    public void withDrive(Consumer<Drive> action) {
        action.accept(requireHandle());
    }

    /** Same, for actions that return a value. */
    public <T> T queryDrive(Function<Drive, T> action) {
        return action.apply(requireHandle());
    }

    private Drive requireHandle() {
        var handle = drive;
        if (handle == null) {
            throw new DriveUnavailableException("Frequency inverter drive handle is not open");
        }
        return handle;
    }

    /**
     * Runs {@code action} under the lifecycle lock and the instance monitor, without requiring an
     * open handle: no {@code connect()}, {@code disconnect()} or {@code reset()} lands inside it. For
     * the safe stop's tier 2, which closes the handle and brings its own fresh one; drive calls on
     * other threads are not held.
     * <p>
     * The wait for the lifecycle lock is bounded by {@link #RUN_EXCLUSIVE_WAIT_MS}, interrupts
     * included. A {@code disconnect()} already inside holds it up to about 5 s (the
     * {@value #POLL_THREAD_JOIN_TIMEOUT_MS} ms poll-thread join, then a pending close retried and the
     * handle closed, each up to the contract's 1500 ms close bound on a gated handle); a
     * {@code connect()} or {@code reset()} for as long as {@link DriveProvider#open()} takes, which
     * the contract does not bound. Past the bound nothing runs: no handle is closed, opened or
     * marked lost. The monitor then follows without a long wait: every holder of it that waits on
     * the drive or the poll thread holds the lifecycle lock too, or runs inside tier 2
     * ({@link #closeDriveHandle}, {@link #invalidateConnection}).
     *
     * @throws LifecycleLockTimeoutException if the lock was not free within the bound; its message
     *                                       names the holder
     */
    public void runExclusive(Runnable action) {
        runExclusive(action, RUN_EXCLUSIVE_WAIT_MS);
    }

    /** With another bound, so a test need not wait out the production one. */
    void runExclusive(Runnable action, long waitMs) {
        if (!tryLockLifecycle("safe stop tier 2", waitMs)) {
            var holder = describeLifecycleHolder();
            throw new LifecycleLockTimeoutException("the frequency inverter's lifecycle lock was not free"
                    + " within " + waitMs + " ms, held by "
                    + (holder == null ? "a holder that released it as the wait ended" : holder));
        }
        try {
            synchronized (this) {
                action.run();
            }
        } finally {
            unlockLifecycle();
        }
    }

    /**
     * Closes and forgets the current handle. Never throws; safe when no handle is open.
     * <p>
     * Bounded by the contract: a gated handle's {@code close()} waits at most 1500 ms. One that gives
     * up is {@link HandleClose#PENDING} and parked for {@link #openConnection} to retry; this method
     * never retries it, so a stop path that calls it twice pays the bound once. With no handle but a
     * parked one it answers PENDING at once.
     */
    public synchronized HandleClose closeDriveHandle() {
        var handle = drive;
        // Unconditional, so withDrive/queryDrive cannot reuse an untrusted handle.
        drive = null;
        if (handle == null) {
            return pendingCloses.isEmpty() ? HandleClose.NONE : HandleClose.PENDING;
        }
        // Best effort: a wedged or unplugged drive may fail to close.
        return close(handle, "the drive handle while invalidating it");
    }

    /**
     * Parks a handle this device never held - the safe stop's tier-2 fresh handle - whose close
     * threw {@link DriveClosePendingException}, so the next connect waits for its port as for a
     * dropped handle's.
     */
    public synchronized void parkPendingClose(Drive handle) {
        pendingCloses.add(handle);
    }

    /**
     * Marks the handle lost ({@link Device#markConnectionLost()}): the holders keep their references
     * and the next {@code connect()} really re-opens the hardware. Never throws.
     */
    void markHandleLost() {
        try {
            markConnectionLost();
        } catch (Throwable t) {
            // Swallowed: on the emergency-stop path this must not abort the teardown that follows.
            logger.warn("Failed to mark the frequency inverter handle lost", t);
        }
    }

    /**
     * Closes the current handle and marks it lost as ONE step under the instance monitor, so no
     * {@code connect()} can run between them: one arriving earlier was counted and its handle is
     * closed here, one arriving later finds the mark and opens the only live session (OQ-50).
     * Never throws.
     */
    public synchronized void invalidateConnection() {
        try {
            closeDriveHandle();
        } catch (Throwable t) {
            logger.warn("Failed to close the drive handle while invalidating the connection", t);
        }
        markHandleLost();
    }

    /** Under the lifecycle lock, like every other poll-thread start and stop. */
    public void registerObserver(InfoObserver observer) {
        lockLifecycle("registerObserver()");
        try {
            observers.add(observer);
            tryStartThread();
        } finally {
            unlockLifecycle();
        }
    }

    /**
     * Under the lifecycle lock: the poll-thread join holds the monitor up to
     * {@value #POLL_THREAD_JOIN_TIMEOUT_MS} ms, and tier 2 bounds only its wait for the lock.
     */
    public void unregisterObserver(InfoObserver observer) {
        lockLifecycle("unregisterObserver()");
        try {
            observers.remove(observer);
            tryStopThread();
        } finally {
            unlockLifecycle();
        }
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
                // Stack trace once per outage, message only afterwards.
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
     * <p>
     * The one place the drive's integers become engineering units: {@code current} and
     * {@code torque} arrive in tenths (A, % of rated torque; {@code Drive#getMotorData}).
     */
    static Info toInfo(Map<String, Integer> motorData, Map<String, Boolean> controlParameters, int id) {
        var info = new Info();
        info.start = require(controlParameters, "control parameters", "start");
        info.generalEnable = require(controlParameters, "control parameters", "generalEnable");
        info.useSecondRamp = require(controlParameters, "control parameters", "useSecondRamp");
        info.directionIsForward = require(controlParameters, "control parameters", "directionIsForward");
        info.speed = require(motorData, "motor data", "speed");
        info.motorCurrent = fromTenths(require(motorData, "motor data", "current"));
        info.motorVoltage = require(motorData, "motor data", "voltage");
        info.motorTorquePercent = fromTenths(require(motorData, "motor data", "torque"));
        info.id = id;
        return info;
    }

    /** A drive value in tenths, such as P0003's 35 for 3.5 A, as the value itself. */
    static double fromTenths(int tenths) {
        return tenths / 10.0;
    }

    /** The driver's failure inside the wrapper, or the wrapper itself when it carries none. */
    private static String describeCause(DriveCommunicationException e) {
        Throwable cause = e.getCause() != null ? e.getCause() : e;
        return cause.getClass().getSimpleName() + ": " + cause.getMessage();
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
                // Two STATUS-lane reads on a gated handle: they queue behind a stop, never ahead of
                // it, and nothing here holds a stop back while they wait.
                var snapshot = snapshot();
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
                // Handle closed, typically after a safe-stop escalation. Logged once per outage,
                // since observers keep showing the last Info.
                if (!driveWasUnavailable) {
                    driveWasUnavailable = true;
                    logger.warn("Drive handle is not open, info polling is idle until it reopens");
                }
            } catch (DriveCommunicationException e) {
                // The link, not the drive: named by the driver's own cause, which the wrapper only
                // carries. Stack trace once per outage, message only afterwards.
                if (!pollWasFailing) {
                    pollWasFailing = true;
                    logger.warn("Frequency inverter did not answer ({}), info polling is idle until it answers",
                            describeCause(e), e);
                } else {
                    logger.debug("Frequency inverter did not answer: {}", describeCause(e));
                }
            } catch (Exception e) {
                // Still Exception, not RuntimeException: a plugin built before device-api 1.4.0 lets
                // the vendor's checked comms exception cross Drive undeclared. Stack trace once per
                // outage, message only afterwards.
                if (!pollWasFailing) {
                    pollWasFailing = true;
                    logger.warn("Failed to poll frequency inverter data, info polling is idle until it answers", e);
                } else {
                    logger.debug("Failed to poll frequency inverter data: {}", e.getMessage());
                }
            }

            // Outside the try, so an observer failure is never read as a drive outage.
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

    /**
     * The motor data and the control word, read on one handle. A read that fails because a safe stop
     * past tier 1 closed that handle under it is the handle gone, not the link.
     *
     * @throws DriveUnavailableException if no handle is open, or it was dropped during the reads
     */
    private DriveSnapshot snapshot() {
        var handle = requireHandle();
        try {
            return new DriveSnapshot(handle.getMotorData(), handle.getControlParameters());
        } catch (Exception e) {
            if (drive != handle) {
                throw new DriveUnavailableException("Frequency inverter drive handle was closed during the poll");
            }
            throw e;
        }
    }

    private record DriveSnapshot(Map<String, Integer> motorData, Map<String, Boolean> controlParameters) {
    }
}
