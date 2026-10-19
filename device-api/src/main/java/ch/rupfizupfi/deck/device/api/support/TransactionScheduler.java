package ch.rupfizupfi.deck.device.api.support;

import ch.rupfizupfi.deck.device.api.DriveClosePendingException;
import ch.rupfizupfi.deck.device.api.DriveCommunicationException;
import ch.rupfizupfi.deck.device.api.DriveDeadlineException;
import ch.rupfizupfi.deck.device.api.DriveLane;
import ch.rupfizupfi.deck.device.api.StaleStopEpochException;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;

/**
 * One drive handle's transactions, run one at a time on one named daemon I/O thread, a stop first.
 * <p>
 * Dispatch order: the STOP queue, then the active {@linkplain LinkedGroup linked group}'s next call
 * (nothing but STOP may run while a group is open), then the earliest {@link DriveLane} with work,
 * first-in first-out within a lane. The STOP queue is lock-free, so submitting a stop never waits
 * for a submitter of other work; the stop then waits for at most the transaction already in flight,
 * since nothing aborts a transfer.
 * <p>
 * Every request carries its lane's deadline from submission. One that is still queued at its
 * deadline is never sent - except a stop, which is idempotent and fail-safe, so it still goes out
 * once the transaction ahead of it ends; one in flight at its deadline fails its future while the
 * I/O thread keeps waiting for the transport. Either way the caller sees
 * {@link DriveDeadlineException}.
 * <p>
 * Every STOP submission bumps the {@linkplain #stopEpoch() stop epoch}. A CONTROL or SETPOINT
 * request submitted before a stop, and every call of a group fenced on an older epoch, fails with
 * {@link StaleStopEpochException} instead of running after it. A stop dispatched while a group is
 * open cuts that group short whatever epoch it was fenced on, so a group never outlives a stop. A
 * stop is never refused for coming from a group's own thread; anything else that thread submits
 * outside its group is refused until it closes the group, even once a stop has cut it.
 * <p>
 * Futures from {@link #submit} complete on virtual threads this scheduler starts, never on the I/O
 * thread, so a dependent stage cannot stall the bus. A synchronous {@link #call} waits for the
 * transaction itself, not for a completion thread, and is refused on the I/O thread.
 * <p>
 * The I/O thread's last act, after its last queued stop, is whatever the provider passed as
 * {@code lastAct} - its port close, so the port closes once the transfer ends even when no closer
 * is still waiting. Whoever calls {@link #close()} waits for the I/O thread at most the STOP
 * deadline, so a wedged transfer can never hold a closer longer than it holds a stop.
 * <p>
 * JDK only: device-api lands on every consumer's classpath, so this class logs nothing and depends
 * on nothing. Anti-starvation, setpoint coalescing and keep-alive injection are a driver's
 * policies and are not here.
 */
public final class TransactionScheduler {

    /** A synchronous call waits this long past its lane deadline for the future's own expiry. */
    private static final long JOIN_SLACK_NANOS = TimeUnit.MILLISECONDS.toNanos(250);

    /** Upper bound on one idle park, so a lost wake-up costs a second, never a hang. */
    private static final long MAX_PARK_NANOS = TimeUnit.SECONDS.toNanos(1);

    private static final DriveLane[] QUEUED_LANES = {
            DriveLane.CONTROL, DriveLane.SETPOINT, DriveLane.STATUS, DriveLane.CONFIG};

    /** An entry's {@link Entry#phase}: still queued, handed to the transport, or past its deadline unsent. */
    private static final int QUEUED = 0;
    private static final int SENT = 1;
    private static final int EXPIRED = 2;

    private final String name;
    private final LaneDeadlines deadlines;
    private final Thread ioThread;
    private final Runnable lastAct;
    /** What {@link #lastAct} threw, until the first {@link #close()} that sees the I/O thread ended reports it. */
    private final AtomicReference<Throwable> lastActFailure = new AtomicReference<>();
    private final Executor completions;
    private final AtomicLong stopEpoch = new AtomicLong();
    private final ConcurrentLinkedQueue<Entry<?>> stopQueue = new ConcurrentLinkedQueue<>();

    private final Object lock = new Object();
    /** Guarded by {@link #lock}. */
    private final Map<DriveLane, ArrayDeque<Entry<?>>> lanes = new EnumMap<>(DriveLane.class);
    /** Written under {@link #lock}; volatile for the idle park. */
    private volatile LinkedGroup activeGroup;
    /**
     * Threads between {@link #openGroup} returning and their group's {@link LinkedGroup#close()}:
     * running a group body, cut or abandoned or not, they may submit nothing but a stop outside it.
     */
    private final Set<Thread> groupOwners = ConcurrentHashMap.newKeySet();
    private volatile boolean closed;
    /** Set by the I/O thread once it has sent the last queued stop; a stop queued later fails. */
    private volatile boolean terminated;
    /** Test seam: runs between a stop's epoch bump and its enqueue. */
    private volatile Runnable afterStopEpochBump = () -> {
    };

    /**
     * Starts the I/O thread, with nothing to do when it ends. A provider on this constructor closes
     * its port itself after {@link #close()} returns normally, so a close that threw
     * {@link DriveClosePendingException} leaves the port open until a later close returns.
     *
     * @param name the I/O thread's name, also the prefix of the completion threads
     */
    public TransactionScheduler(String name, LaneDeadlines deadlines) {
        this(name, deadlines, () -> {
        });
    }

    /**
     * Starts the I/O thread. Since contract 1.6.0.
     *
     * @param name    the I/O thread's name, also the prefix of the completion threads
     * @param lastAct runs once on the I/O thread as it ends, after its last transaction and its last
     *                queued stop - the provider's port close. What it throws reaches the first
     *                {@link #close()} that sees the I/O thread ended
     */
    public TransactionScheduler(String name, LaneDeadlines deadlines, Runnable lastAct) {
        this.name = name;
        this.deadlines = deadlines;
        this.lastAct = Objects.requireNonNull(lastAct, "lastAct");
        for (DriveLane lane : QUEUED_LANES) {
            lanes.put(lane, new ArrayDeque<>());
        }
        var completionThreads = Thread.ofVirtual().name(name + "-completion").factory();
        this.completions = task -> completionThreads.newThread(task).start();
        this.ioThread = Thread.ofPlatform().name(name).daemon(true).unstarted(this::ioLoop);
        ioThread.start();
    }

    /** The I/O thread's name. */
    public String name() {
        return name;
    }

    public LaneDeadlines deadlines() {
        return deadlines;
    }

    public long stopEpoch() {
        return stopEpoch.get();
    }

    public boolean isIoThread() {
        return Thread.currentThread() == ioThread;
    }

    /** True on a thread that has opened a linked group here and not closed it yet. */
    public boolean isInsideOwnGroup() {
        return groupOwners.contains(Thread.currentThread());
    }

    /** Test seam: {@code hook} runs on the submitting thread after a stop's epoch bump, before its enqueue. */
    void afterStopEpochBump(Runnable hook) {
        afterStopEpochBump = hook;
    }

    // ---- submission ---------------------------------------------------------------------------

    /**
     * Queues {@code work} in {@code lane}. A STOP submission bumps the stop epoch before it is
     * queued.
     *
     * @throws IllegalStateException if the scheduler is closed, or the caller has a linked group
     *                               open and must use the group's drive instead - never for a
     *                               STOP-lane call, which dispatches ahead of the group anyway
     */
    public <T> CompletableFuture<T> submit(DriveLane lane, String operation, Callable<T> work) {
        requireOutsideOwnGroup(lane, operation);
        return publish(enqueue(new Entry<>(lane, operation, work, null, null)));
    }

    /**
     * {@link #submit}, then waits for the reply; the calling convention of every sync method. It
     * waits for the transaction itself, so a slow completion thread cannot turn a stop that went
     * out into a deadline failure.
     */
    public <T> T call(DriveLane lane, String operation, Callable<T> work) {
        requireNotIoThread(operation);
        requireOutsideOwnGroup(lane, operation);
        return await(enqueue(new Entry<>(lane, operation, work, null, null)));
    }

    /**
     * One call of {@code group}. A STOP-lane call goes to the STOP queue like any other stop; the
     * rest run only while the group is the active one and no stop has cut it.
     */
    public <T> T callLinked(LinkedGroup group, DriveLane lane, String operation, Callable<T> work) {
        requireNotIoThread(operation);
        return await(enqueue(new Entry<>(lane, operation, work, lane == DriveLane.STOP ? null : group, null)));
    }

    /**
     * Opens a linked group fenced on {@code expectedEpoch}: waits, in the CONTROL lane, until the I/O
     * thread admits it. The caller runs its calls through {@link #callLinked} and then closes it.
     *
     * @throws StaleStopEpochException if a stop was submitted since {@code expectedEpoch} was read
     */
    public LinkedGroup openGroup(long expectedEpoch) {
        requireNotIoThread("a linked group");
        requireNotGroupOwner("a nested linked group");
        long now = stopEpoch.get();
        if (expectedEpoch != now) {
            throw new StaleStopEpochException("linked group refused before it ran: a stop intervened",
                    expectedEpoch, now);
        }
        var group = new LinkedGroup(expectedEpoch, Thread.currentThread());
        try {
            await(enqueue(new Entry<LinkedGroup>(DriveLane.CONTROL, "linked group", null, null, group)));
        } catch (RuntimeException e) {
            // A late admission then finds it closed and skips it.
            group.close();
            throw e;
        }
        groupOwners.add(group.owner);
        return group;
    }

    private <T> Entry<T> enqueue(Entry<T> entry) {
        if (closed) {
            throw closedException(entry.operation);
        }
        entry.raw.orTimeout(deadlines.of(entry.lane).toNanos(), TimeUnit.NANOSECONDS);
        if (entry.lane == DriveLane.STOP) {
            stopEpoch.incrementAndGet();
            afterStopEpochBump.run();
            stopQueue.add(entry);
            if (terminated) {
                // The I/O thread may have drained the queue for the last time before the add.
                fail(entry, closedBeforeSent(entry));
            }
        } else {
            synchronized (lock) {
                if (closed) {
                    throw closedException(entry.operation);
                }
                if (entry.group != null) {
                    long now = stopEpoch.get();
                    if (entry.group.cut || entry.group.expectedEpoch != now) {
                        fail(entry, staleGroup(entry, now));
                        return entry;
                    }
                    if (entry.group.closed) {
                        throw new IllegalStateException(entry.operation + ": the linked group "
                                + (entry.group.abandoned ? "was abandoned after submitting nothing for "
                                + deadlines.control().toMillis() + " ms" : "has ended"));
                    }
                    entry.group.queue.add(entry);
                } else {
                    lanes.get(entry.lane).add(entry);
                }
            }
        }
        LockSupport.unpark(ioThread);
        return entry;
    }

    /**
     * The async caller's future: completed off the I/O thread, with the provider's failure
     * translated so a caller never sees a wrapper or a bare timeout.
     */
    private <T> CompletableFuture<T> publish(Entry<T> entry) {
        var published = new CompletableFuture<T>();
        entry.raw.whenCompleteAsync((value, failure) -> {
            if (failure == null) {
                published.complete(value);
            } else {
                published.completeExceptionally(translate(entry, failure));
            }
        }, completions);
        return published;
    }

    private Throwable translate(Entry<?> entry, Throwable failure) {
        Throwable cause = unwrap(failure);
        if (cause instanceof TimeoutException) {
            // The phase decides, atomically with dispatch, whether the request goes out.
            boolean sent = !entry.phase.compareAndSet(QUEUED, EXPIRED) && entry.phase.get() == SENT;
            String fate = sent ? "sent; the transaction is still in flight"
                    : entry.lane == DriveLane.STOP
                    ? "not sent yet; a stop still goes out once the transaction ahead of it ends"
                    : "never sent; it was still queued";
            return new DriveDeadlineException(entry.lane, entry.operation + " on " + name
                    + ": no reply within the " + entry.lane + " lane's "
                    + deadlines.of(entry.lane).toMillis() + " ms deadline (" + fate + ")");
        }
        if (cause instanceof RuntimeException || cause instanceof Error) {
            return cause;
        }
        return new DriveCommunicationException(entry.operation + " on " + name + " failed", cause);
    }

    private static Throwable unwrap(Throwable failure) {
        Throwable t = failure;
        while ((t instanceof CompletionException || t instanceof ExecutionException) && t.getCause() != null) {
            t = t.getCause();
        }
        return t;
    }

    /**
     * Waits for the entry's transaction at most its lane's deadline plus a slack, uninterruptibly -
     * like the transfer it waits for. An interrupt is kept for the caller to see afterwards. A
     * failure is rethrown with its own type, carrying the caller's stack as a suppressed exception.
     */
    private <T> T await(Entry<T> entry) {
        DriveLane lane = entry.lane;
        String operation = entry.operation;
        long end = System.nanoTime() + deadlines.of(lane).toNanos() + JOIN_SLACK_NANOS;
        boolean interrupted = false;
        try {
            while (true) {
                try {
                    return entry.raw.get(Math.max(0, end - System.nanoTime()), TimeUnit.NANOSECONDS);
                } catch (InterruptedException e) {
                    interrupted = true;
                } catch (TimeoutException e) {
                    throw new DriveDeadlineException(lane, operation + " on " + name
                            + ": no reply within the " + lane + " lane's "
                            + deadlines.of(lane).toMillis() + " ms deadline");
                } catch (ExecutionException e) {
                    throw rethrowable(translate(entry, e.getCause()), operation);
                } catch (CancellationException e) {
                    throw new IllegalStateException(operation + " on " + name + " was cancelled", e);
                }
            }
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private RuntimeException rethrowable(Throwable cause, String operation) {
        Throwable t = unwrap(cause);
        if (t instanceof Error error) {
            throw error;
        }
        RuntimeException failure = t instanceof RuntimeException runtime
                ? runtime
                : new DriveCommunicationException(operation + " on " + name + " failed", t);
        failure.addSuppressed(new Exception("awaited by " + Thread.currentThread().getName()));
        return failure;
    }

    // ---- the I/O thread -----------------------------------------------------------------------

    private void ioLoop() {
        try {
            while (true) {
                // Nobody interrupts this thread on purpose, and a set flag would make every parkNanos
                // return at once: an operation that re-asserts an interrupt must not spin the loop.
                Thread.interrupted();
                Entry<?> stop = stopQueue.poll();
                if (stop != null) {
                    dispatchStop(stop);
                    continue;
                }
                Entry<?> next;
                long parkNanos;
                synchronized (lock) {
                    if (closed) {
                        break;
                    }
                    next = nextLocked();
                    parkNanos = next == null ? idleParkNanosLocked() : 0;
                }
                if (next != null) {
                    dispatch(next);
                } else {
                    LockSupport.parkNanos(this, parkNanos);
                }
            }
            // Closed: close() failed the other queued work, but every queued stop still goes out
            // before the port closes. A stop that slips in after the last drain fails instead.
            drainStops();
            terminated = true;
            drainStops();
        } finally {
            terminate();
        }
    }

    private void drainStops() {
        for (Entry<?> stop; (stop = stopQueue.poll()) != null; ) {
            dispatchStop(stop);
        }
    }

    private Entry<?> nextLocked() {
        LinkedGroup group = activeGroup;
        if (group != null) {
            if (group.closed) {
                failAll(group.queue, closedGroup(group));
                activeGroup = null;
            } else {
                Entry<?> e = group.queue.poll();
                if (e != null) {
                    return e;
                }
                if (System.nanoTime() - group.lastActivityNanos > deadlines.control().toNanos()) {
                    group.abandoned = true;
                    group.closed = true;
                    activeGroup = null;
                } else {
                    return null;
                }
            }
        }
        for (DriveLane lane : QUEUED_LANES) {
            Entry<?> e = lanes.get(lane).poll();
            if (e != null) {
                return e;
            }
        }
        return null;
    }

    private long idleParkNanosLocked() {
        LinkedGroup group = activeGroup;
        if (group == null) {
            return MAX_PARK_NANOS;
        }
        long left = deadlines.control().toNanos() - (System.nanoTime() - group.lastActivityNanos);
        return Math.clamp(left + 1, 1, MAX_PARK_NANOS);
    }

    private void dispatchStop(Entry<?> stop) {
        synchronized (lock) {
            failStaleLocked();
            cutActiveGroupLocked();
        }
        dispatch(stop);
    }

    /**
     * A stop going out ends the open group, whatever epoch it was fenced on: its epoch may have been
     * read after the stop was submitted but before the stop was queued, so the epoch alone cannot
     * tell that the stop overtook it.
     */
    private void cutActiveGroupLocked() {
        LinkedGroup group = activeGroup;
        if (group == null) {
            return;
        }
        group.cut = true;
        activeGroup = null;
        long now = stopEpoch.get();
        for (Entry<?> e; (e = group.queue.poll()) != null; ) {
            fail(e, staleGroup(e, now));
        }
    }

    /** Fails every queued request the stop epoch has overtaken, so none runs after the stop. */
    private void failStaleLocked() {
        long now = stopEpoch.get();
        for (DriveLane lane : List.of(DriveLane.CONTROL, DriveLane.SETPOINT)) {
            for (Iterator<Entry<?>> it = lanes.get(lane).iterator(); it.hasNext(); ) {
                Entry<?> e = it.next();
                StaleStopEpochException stale = staleness(e, now);
                if (stale != null) {
                    it.remove();
                    fail(e, stale);
                }
            }
        }
    }

    /**
     * Sends {@code entry} unless it is past its deadline or overtaken by a stop. A STOP entry past
     * its deadline is sent anyway: its caller already has the deadline failure, but a stop is
     * idempotent and stopping is the safe direction, while dropping it would leave the drive running
     * on a handle that just recovered.
     */
    private <T> void dispatch(Entry<T> entry) {
        if (!entry.claimed.compareAndSet(false, true)) {
            return;
        }
        if (entry.lane == DriveLane.STOP) {
            entry.phase.compareAndSet(QUEUED, SENT);
        } else {
            if (entry.raw.isDone() || System.nanoTime() - entry.deadlineNanos > 0) {
                entry.phase.compareAndSet(QUEUED, EXPIRED);
                entry.raw.completeExceptionally(new TimeoutException());
                return;
            }
            StaleStopEpochException stale = staleness(entry, stopEpoch.get());
            if (stale != null) {
                entry.raw.completeExceptionally(stale);
                return;
            }
            if (entry.admits != null) {
                admit(entry);
                return;
            }
            if (!entry.phase.compareAndSet(QUEUED, SENT)) {
                // The deadline fired between the check above and now; its caller was told "never sent".
                return;
            }
        }
        try {
            entry.raw.complete(entry.work.call());
        } catch (Throwable t) {
            entry.raw.completeExceptionally(t);
        } finally {
            if (entry.group != null) {
                entry.group.lastActivityNanos = System.nanoTime();
            }
        }
    }

    @SuppressWarnings("unchecked")
    private <T> void admit(Entry<T> admission) {
        LinkedGroup group = admission.admits;
        synchronized (lock) {
            if (group.closed) {
                return;
            }
            // Active before the owner can see the admission, so its first call finds the group.
            group.lastActivityNanos = System.nanoTime();
            activeGroup = group;
            if (!((Entry<LinkedGroup>) admission).raw.complete(group)) {
                activeGroup = null;
            }
        }
    }

    private StaleStopEpochException staleness(Entry<?> e, long now) {
        if (e.admits != null) {
            return e.admits.expectedEpoch == now ? null
                    : new StaleStopEpochException("linked group refused before it ran: a stop intervened",
                    e.admits.expectedEpoch, now);
        }
        if (e.group != null) {
            return !e.group.cut && e.group.expectedEpoch == now ? null : staleGroup(e, now);
        }
        if ((e.lane == DriveLane.CONTROL || e.lane == DriveLane.SETPOINT) && e.epochAtSubmit != now) {
            return new StaleStopEpochException(e.operation + " cancelled: a stop was submitted after it",
                    e.epochAtSubmit, now);
        }
        return null;
    }

    private static StaleStopEpochException staleGroup(Entry<?> e, long now) {
        return new StaleStopEpochException(e.operation + " not sent: a stop cut its linked group short",
                e.group.expectedEpoch, now);
    }

    // ---- close --------------------------------------------------------------------------------

    /**
     * Rejects every later submission at once - a stop included - and fails every queued request
     * with {@link IllegalStateException}, except a stop queued before: the I/O thread sends those
     * once the transaction in flight has ended, runs its last act and ends. Returns once it has
     * ended, so nothing is in flight any more and the last act (the port close, if the provider
     * passed one) has run.
     * <p>
     * Waits at most the STOP deadline: nothing aborts a transfer, so a wedged one would otherwise
     * hold the closer as long as it lasts. Past that bound it throws, and the I/O thread runs its
     * last act by itself once the transfer ends. Idempotent: a later call waits the same bound
     * and returns once the I/O thread has ended.
     *
     * @throws DriveClosePendingException if the I/O thread is still running after the STOP
     *                                    deadline; the port is then still open
     * @throws IllegalStateException      on the I/O thread, which would wait for itself
     * @throws RuntimeException           what the last act threw, to the first call that sees the
     *                                    I/O thread ended (a checked one as
     *                                    {@link DriveCommunicationException})
     */
    public void close() {
        if (isIoThread()) {
            throw new IllegalStateException("close() called on " + name
                    + "'s own I/O thread, which would wait for itself");
        }
        List<Entry<?>> queued = new ArrayList<>();
        synchronized (lock) {
            if (!closed) {
                closed = true;
                for (ArrayDeque<Entry<?>> lane : lanes.values()) {
                    queued.addAll(lane);
                    lane.clear();
                }
                LinkedGroup group = activeGroup;
                if (group != null) {
                    queued.addAll(group.queue);
                    group.queue.clear();
                    group.closed = true;
                    activeGroup = null;
                }
            }
        }
        for (Entry<?> e : queued) {
            fail(e, closedBeforeSent(e));
        }
        LockSupport.unpark(ioThread);
        if (!joinUninterruptibly(deadlines.stop().toNanos())) {
            throw new DriveClosePendingException("close() of drive handle " + name
                    + ": the transaction in flight has not ended within the "
                    + deadlines.stop().toMillis() + " ms STOP deadline, so the port is still open."
                    + " The handle takes no more calls; once the transfer ends, the stops queued"
                    + " before close() go out, and a later close() returns once the port is closed");
        }
        Throwable failure = lastActFailure.getAndSet(null);
        if (failure instanceof RuntimeException runtime) {
            throw runtime;
        }
        if (failure instanceof Error error) {
            throw error;
        }
        if (failure != null) {
            throw new DriveCommunicationException("closing the port of " + name + " failed", failure);
        }
    }

    /**
     * True once the I/O thread has ended: nothing is in flight, nothing more will be sent and its
     * last act has run.
     */
    public boolean isTerminated() {
        return !ioThread.isAlive();
    }

    /**
     * Fails whatever is left - only an Error escaping the loop leaves work here - then runs the last
     * act, so the port closes on every way out of the loop.
     */
    private void terminate() {
        terminated = true;
        try {
            synchronized (lock) {
                closed = true;
                for (ArrayDeque<Entry<?>> lane : lanes.values()) {
                    failAll(lane, new IllegalStateException(name + " stopped dispatching"));
                }
            }
            for (Entry<?> e; (e = stopQueue.poll()) != null; ) {
                fail(e, closedBeforeSent(e));
            }
        } finally {
            try {
                lastAct.run();
            } catch (Throwable t) {
                lastActFailure.set(t);
            }
        }
    }

    /** True if the I/O thread ended within {@code boundNanos}; an interrupt is kept, not obeyed. */
    private boolean joinUninterruptibly(long boundNanos) {
        long end = System.nanoTime() + boundNanos;
        boolean interrupted = false;
        try {
            while (ioThread.isAlive()) {
                long left = end - System.nanoTime();
                if (left <= 0) {
                    return false;
                }
                try {
                    ioThread.join(Duration.ofNanos(left));
                } catch (InterruptedException e) {
                    interrupted = true;
                }
            }
            return true;
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    // ---- helpers ------------------------------------------------------------------------------

    private void failAll(ArrayDeque<Entry<?>> queue, RuntimeException failure) {
        for (Entry<?> e; (e = queue.poll()) != null; ) {
            fail(e, failure);
        }
    }

    private static void fail(Entry<?> e, RuntimeException failure) {
        if (e.claimed.compareAndSet(false, true)) {
            e.raw.completeExceptionally(failure);
        }
    }

    private IllegalStateException closedException(String operation) {
        return new IllegalStateException(operation + ": drive handle " + name + " is closed");
    }

    private IllegalStateException closedBeforeSent(Entry<?> e) {
        return new IllegalStateException(e.operation + ": drive handle " + name
                + " was closed before the request was sent");
    }

    private static IllegalStateException closedGroup(LinkedGroup group) {
        return new IllegalStateException("linked group fenced on stop epoch " + group.expectedEpoch
                + " has ended");
    }

    private void requireNotIoThread(String operation) {
        if (isIoThread()) {
            throw new IllegalStateException(operation + " called synchronously on " + name
                    + "'s own I/O thread, which would wait for itself");
        }
    }

    /** A stop is never refused: it goes to the lock-free STOP queue and cannot wait on the group. */
    private void requireOutsideOwnGroup(DriveLane lane, String operation) {
        if (lane != DriveLane.STOP) {
            requireNotGroupOwner(operation);
        }
    }

    /**
     * Holds for the whole body, not only while the group is active: once a stop cut it or it was
     * abandoned, a call the body made on the handle would be stamped with the new epoch and go out
     * after the stop.
     */
    private void requireNotGroupOwner(String operation) {
        if (isInsideOwnGroup()) {
            throw new IllegalStateException(operation + " called on the drive handle from inside its"
                    + " own linked group's body; use the Drive passed to the body - only a stop goes"
                    + " to the handle there");
        }
    }

    /**
     * A linked group, open from admission until {@link #close()}, or until a stop cuts it or it is
     * abandoned. Only the thread that opened it submits to it, and that thread submits nothing but
     * a stop outside it until it closes it.
     */
    public final class LinkedGroup implements AutoCloseable {

        private final long expectedEpoch;
        private final Thread owner;
        /** Guarded by the scheduler's lock. */
        private final ArrayDeque<Entry<?>> queue = new ArrayDeque<>();
        private volatile boolean closed;
        private volatile boolean abandoned;
        /** Set when a stop went out while this group was open; every later call of it fails. */
        private volatile boolean cut;
        private volatile long lastActivityNanos = System.nanoTime();
        private final AtomicBoolean released = new AtomicBoolean();

        private LinkedGroup(long expectedEpoch, Thread owner) {
            this.expectedEpoch = expectedEpoch;
            this.owner = owner;
        }

        public long expectedEpoch() {
            return expectedEpoch;
        }

        /** Ends the group and its owner's body; the I/O thread goes back to the lanes. Idempotent. */
        @Override
        public void close() {
            closed = true;
            if (released.compareAndSet(false, true)) {
                groupOwners.remove(owner);
            }
            LockSupport.unpark(ioThread);
        }
    }

    private final class Entry<T> {

        final DriveLane lane;
        final String operation;
        final Callable<T> work;
        /** The group this call belongs to; null outside one. */
        final LinkedGroup group;
        /** The group this entry admits; null for every entry that is a call. */
        final LinkedGroup admits;
        final long deadlineNanos;
        final long epochAtSubmit;
        final CompletableFuture<T> raw = new CompletableFuture<>();
        /** Set by whichever of the I/O thread and a failing path gets to the entry first. */
        final AtomicBoolean claimed = new AtomicBoolean();
        /** {@link #QUEUED}, then {@link #SENT} by the I/O thread or {@link #EXPIRED} by the deadline. */
        final AtomicInteger phase = new AtomicInteger(QUEUED);

        Entry(DriveLane lane, String operation, Callable<T> work, LinkedGroup group, LinkedGroup admits) {
            this.lane = lane;
            this.operation = operation;
            this.work = work;
            this.group = group;
            this.admits = admits;
            this.deadlineNanos = System.nanoTime() + deadlines.of(lane).toNanos();
            this.epochAtSubmit = stopEpoch.get();
        }
    }
}
