package ch.rupfizupfi.deck.device.api.support;

import ch.rupfizupfi.deck.device.api.DriveClosePendingException;
import ch.rupfizupfi.deck.device.api.DriveCommunicationException;
import ch.rupfizupfi.deck.device.api.DriveDeadlineException;
import ch.rupfizupfi.deck.device.api.DriveLane;
import ch.rupfizupfi.deck.device.api.StaleStopEpochException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The scheduler against a fake transport: each transaction records its name and sleeps its latency
 * on the I/O thread. Latencies are large against scheduling jitter and the slack is 250 ms, so the
 * timing bounds hold on a loaded 2-vCPU runner without a retry while still telling one transaction
 * in flight (bound 550 ms) from seven (1050 ms).
 */
@Timeout(value = 20, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class TransactionSchedulerTest {

    private static final long SLOW = 150;
    private static final long SLACK = 250;

    private static final LaneDeadlines GENEROUS = LaneDeadlines.of(Duration.ofSeconds(2), Duration.ofSeconds(2),
            Duration.ofSeconds(2), Duration.ofSeconds(2));

    private final List<String> sent = new CopyOnWriteArrayList<>();
    private final List<TransactionScheduler> opened = new ArrayList<>();

    @AfterEach
    void closeAll() {
        opened.forEach(TransactionScheduler::close);
    }

    private TransactionScheduler scheduler(LaneDeadlines deadlines) {
        var scheduler = new TransactionScheduler("test-io", deadlines);
        opened.add(scheduler);
        return scheduler;
    }

    private TransactionScheduler scheduler(LaneDeadlines deadlines, Runnable lastAct) {
        var scheduler = new TransactionScheduler("test-io", deadlines, lastAct);
        opened.add(scheduler);
        return scheduler;
    }

    private static void awaitTerminated(TransactionScheduler scheduler) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!scheduler.isTerminated() && System.nanoTime() < deadline) {
            sleep(10);
        }
        assertThat(scheduler.isTerminated()).as("the I/O thread ended").isTrue();
    }

    @SuppressWarnings("unchecked")
    private static <E extends Throwable> void sneakyThrow(Throwable t) throws E {
        throw (E) t;
    }

    /** A transaction of the fake transport. */
    private Callable<String> tx(String name, long latencyMillis) {
        return () -> {
            sent.add(name);
            Thread.sleep(latencyMillis);
            return name;
        };
    }

    private static long millisSince(long startedNanos) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos);
    }

    // ---- order ------------------------------------------------------------------------------

    @Test
    void aLaneIsFirstInFirstOut() {
        var scheduler = scheduler(GENEROUS);
        scheduler.submit(DriveLane.STATUS, "slow", tx("slow", SLOW));
        sleep(SLOW / 3);

        List<CompletableFuture<String>> reads = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            reads.add(scheduler.submit(DriveLane.STATUS, "read" + i, tx("read" + i, 1)));
        }
        reads.forEach(CompletableFuture::join);

        assertThat(sent).containsExactly("slow", "read0", "read1", "read2", "read3", "read4");
    }

    @Test
    void anEarlierLaneGoesFirst() {
        var scheduler = scheduler(GENEROUS);
        scheduler.submit(DriveLane.STATUS, "slow", tx("slow", SLOW));
        sleep(SLOW / 3);

        var status = scheduler.submit(DriveLane.STATUS, "status", tx("status", 1));
        var config = scheduler.submit(DriveLane.CONFIG, "config", tx("config", 1));
        var setpoint = scheduler.submit(DriveLane.SETPOINT, "setpoint", tx("setpoint", 1));
        var control = scheduler.submit(DriveLane.CONTROL, "control", tx("control", 1));
        CompletableFuture.allOf(status, config, setpoint, control).join();

        assertThat(sent).containsExactly("slow", "control", "setpoint", "status", "config");
    }

    @Test
    void aStopOvertakesEverythingQueuedAndWaitsOnlyForTheTransactionInFlight() {
        var scheduler = scheduler(GENEROUS);
        scheduler.submit(DriveLane.STATUS, "inFlight", tx("inFlight", SLOW));
        sleep(SLOW / 3);
        for (int i = 0; i < 6; i++) {
            scheduler.submit(DriveLane.STATUS, "read" + i, tx("read" + i, SLOW));
        }

        long started = System.nanoTime();
        scheduler.call(DriveLane.STOP, "stop", tx("stop", SLOW));

        assertThat(millisSince(started)).isLessThanOrEqualTo(2 * SLOW + SLACK);
        assertThat(sent).startsWith("inFlight", "stop");
    }

    // ---- deadlines --------------------------------------------------------------------------

    @Test
    void aRequestStillQueuedAtItsDeadlineIsNeverSent() {
        var scheduler = scheduler(LaneDeadlines.of(Duration.ofSeconds(2), Duration.ofSeconds(2),
                Duration.ofSeconds(2), Duration.ofMillis(100)));
        scheduler.submit(DriveLane.CONFIG, "slowConfig", tx("slowConfig", 3 * SLOW));
        sleep(SLOW / 3);

        long started = System.nanoTime();
        assertThatThrownBy(() -> scheduler.call(DriveLane.STATUS, "read", tx("read", 1)))
                .isInstanceOf(DriveDeadlineException.class)
                .hasMessageContaining("never sent")
                .satisfies(e -> assertThat(((DriveDeadlineException) e).lane()).isEqualTo(DriveLane.STATUS));
        assertThat(millisSince(started)).isLessThan(3 * SLOW);

        sleep(3 * SLOW);
        assertThat(sent).containsExactly("slowConfig");
    }

    /**
     * A stop is idempotent and stopping is the safe direction, so one still queued at its deadline
     * is sent late rather than dropped; its caller already has the deadline failure.
     */
    @Test
    void aStopStillQueuedAtItsDeadlineFailsItsCallerButStillGoesOut() {
        var scheduler = scheduler(new LaneDeadlines(Duration.ofMillis(100), Duration.ofSeconds(2),
                Duration.ofSeconds(2), Duration.ofSeconds(2), Duration.ofSeconds(2)));
        var wedge = scheduler.submit(DriveLane.STATUS, "wedge", tx("wedge", 3 * SLOW));
        sleep(SLOW / 3);

        long started = System.nanoTime();
        assertThatThrownBy(() -> scheduler.call(DriveLane.STOP, "stop", tx("stop", 1)))
                .isInstanceOf(DriveDeadlineException.class)
                .hasMessageContaining("a stop still goes out")
                .satisfies(e -> assertThat(((DriveDeadlineException) e).lane()).isEqualTo(DriveLane.STOP));
        assertThat(millisSince(started)).isLessThan(3 * SLOW);
        assertThat(sent).as("nothing overtakes the transaction in flight").containsExactly("wedge");

        wedge.join();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (!sent.contains("stop") && System.nanoTime() < deadline) {
            sleep(10);
        }
        assertThat(sent).as("the stop went out once the wedge ended").containsExactly("wedge", "stop");
    }

    @Test
    void aTransactionThatOutlastsItsDeadlineFailsTheCallerWhileTheIoThreadFinishesIt() {
        var scheduler = scheduler(LaneDeadlines.of(Duration.ofSeconds(2), Duration.ofSeconds(2),
                Duration.ofSeconds(2), Duration.ofMillis(100)));
        var finished = new CountDownLatch(1);

        long started = System.nanoTime();
        assertThatThrownBy(() -> scheduler.call(DriveLane.STATUS, "wedged", () -> {
            Thread.sleep(3 * SLOW);
            finished.countDown();
            return 0;
        })).isInstanceOf(DriveDeadlineException.class).hasMessageContaining("still in flight");
        assertThat(millisSince(started)).isLessThan(3 * SLOW);

        assertThat(await(finished)).as("the I/O thread finished the transaction").isTrue();
    }

    // ---- stop epoch -------------------------------------------------------------------------

    @Test
    void everyStopSubmissionBumpsTheEpochByOne() {
        var scheduler = scheduler(GENEROUS);
        assertThat(scheduler.stopEpoch()).isZero();

        scheduler.call(DriveLane.STOP, "stop", tx("stop", 1));
        scheduler.call(DriveLane.STATUS, "read", tx("read", 1));
        scheduler.call(DriveLane.CONTROL, "start", tx("start", 1));
        assertThat(scheduler.stopEpoch()).isEqualTo(1);

        CompletableFuture.allOf(scheduler.submit(DriveLane.STOP, "a", tx("a", 1)),
                scheduler.submit(DriveLane.STOP, "b", tx("b", 1))).join();
        assertThat(scheduler.stopEpoch()).isEqualTo(3);
    }

    @Test
    void aStopFailsQueuedControlAndSetpointWorkButNotReads() {
        var scheduler = scheduler(GENEROUS);
        scheduler.submit(DriveLane.STATUS, "inFlight", tx("inFlight", SLOW));
        sleep(SLOW / 3);
        var start = scheduler.submit(DriveLane.CONTROL, "start", tx("start", 1));
        var speed = scheduler.submit(DriveLane.SETPOINT, "speed", tx("speed", 1));
        var read = scheduler.submit(DriveLane.STATUS, "read", tx("read", 1));
        var config = scheduler.submit(DriveLane.CONFIG, "config", tx("config", 1));

        scheduler.call(DriveLane.STOP, "stop", tx("stop", 1));

        assertStale(start);
        assertStale(speed);
        assertThat(read.join()).isEqualTo("read");
        assertThat(config.join()).isEqualTo("config");
        assertThat(sent).containsExactly("inFlight", "stop", "read", "config");
    }

    @Test
    void workSubmittedAfterAStopRuns() {
        var scheduler = scheduler(GENEROUS);
        scheduler.call(DriveLane.STOP, "stop", tx("stop", 1));

        assertThat(scheduler.call(DriveLane.CONTROL, "start", tx("start", 1))).isEqualTo("start");
    }

    // ---- linked groups ----------------------------------------------------------------------

    @Test
    void aGroupWithAStaleEpochRunsNothing() {
        var scheduler = scheduler(GENEROUS);
        long epoch = scheduler.stopEpoch();
        scheduler.call(DriveLane.STOP, "stop", tx("stop", 1));

        assertThatThrownBy(() -> scheduler.openGroup(epoch)).isInstanceOf(StaleStopEpochException.class);
        assertThat(sent).containsExactly("stop");
    }

    @Test
    void aStopQueuedBeforeAdmissionRejectsTheGroup() {
        var scheduler = scheduler(GENEROUS);
        scheduler.submit(DriveLane.STATUS, "inFlight", tx("inFlight", SLOW));
        sleep(SLOW / 3);
        long epoch = scheduler.stopEpoch();
        var admitted = new AtomicReference<Throwable>();
        Thread opener = Thread.ofPlatform().start(() -> {
            try {
                scheduler.openGroup(epoch).close();
            } catch (Throwable t) {
                admitted.set(t);
            }
        });
        sleep(SLOW / 5);

        scheduler.call(DriveLane.STOP, "stop", tx("stop", 1));
        join(opener);

        assertThat(admitted.get()).isInstanceOf(StaleStopEpochException.class);
    }

    @Test
    void nothingButAStopRunsBetweenAGroupsCalls() {
        var scheduler = scheduler(GENEROUS);
        CompletableFuture<String> outside;

        try (var group = scheduler.openGroup(scheduler.stopEpoch())) {
            scheduler.callLinked(group, DriveLane.SETPOINT, "g1", tx("g1", 20));
            outside = fromAnotherThread(() -> scheduler.submit(DriveLane.STATUS, "outside", tx("outside", 1)));
            sleep(SLOW);
            scheduler.callLinked(group, DriveLane.CONTROL, "g2", tx("g2", 20));
        }
        outside.join();

        assertThat(sent).containsExactly("g1", "g2", "outside");
    }

    @Test
    void aStopDuringAGroupRunsBetweenItsCallsAndCutsItShort() {
        var scheduler = scheduler(GENEROUS);
        CompletableFuture<String> outside;

        try (var group = scheduler.openGroup(scheduler.stopEpoch())) {
            scheduler.callLinked(group, DriveLane.SETPOINT, "g1", tx("g1", 20));
            outside = fromAnotherThread(() -> scheduler.submit(DriveLane.STATUS, "outside", tx("outside", 1)));
            Thread stopper = Thread.ofPlatform().start(() -> scheduler.call(DriveLane.STOP, "stop", tx("stop", 1)));
            join(stopper);

            assertThatThrownBy(() -> scheduler.callLinked(group, DriveLane.CONTROL, "g2", tx("g2", 1)))
                    .isInstanceOf(StaleStopEpochException.class);
        }
        outside.join();

        assertThat(sent).containsExactly("g1", "stop", "outside");
    }

    /**
     * The group's epoch is read after a stop bumped it but before the stop reached the queue, so the
     * group is admitted and runs; the stop going out must still cut it, or its later calls would
     * follow a stop that already returned.
     */
    @Test
    void aStopDispatchedDuringAGroupCutsItWhateverEpochTheGroupRead() {
        var scheduler = scheduler(GENEROUS);
        var bumped = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        scheduler.afterStopEpochBump(() -> {
            bumped.countDown();
            await(release);
        });
        Thread stopper = Thread.ofPlatform().start(() -> scheduler.call(DriveLane.STOP, "stop", tx("stop", 1)));
        assertThat(await(bumped)).as("the stop bumped the epoch").isTrue();

        try (var group = scheduler.openGroup(scheduler.stopEpoch())) {
            assertThat(group.expectedEpoch()).as("fenced on the post-bump epoch").isEqualTo(1);
            scheduler.callLinked(group, DriveLane.SETPOINT, "g1", tx("g1", 1));
            release.countDown();
            join(stopper);

            assertThatThrownBy(() -> scheduler.callLinked(group, DriveLane.CONTROL, "g2", tx("g2", 1)))
                    .isInstanceOf(StaleStopEpochException.class);
        }

        assertThat(sent).containsExactly("g1", "stop");
    }

    /** A stop dispatches ahead of the group anyway, so refusing one protects nothing. */
    @Test
    void aStopFromTheGroupsOwnThreadGoesOutAndCutsTheGroupShort() {
        var scheduler = scheduler(GENEROUS);

        try (var group = scheduler.openGroup(scheduler.stopEpoch())) {
            scheduler.callLinked(group, DriveLane.SETPOINT, "g1", tx("g1", 1));
            assertThat(scheduler.call(DriveLane.STOP, "stop", tx("stop", 1))).isEqualTo("stop");
            assertThat(scheduler.submit(DriveLane.STOP, "asyncStop", tx("asyncStop", 1)).join())
                    .isEqualTo("asyncStop");

            assertThatThrownBy(() -> scheduler.callLinked(group, DriveLane.CONTROL, "g2", tx("g2", 1)))
                    .isInstanceOf(StaleStopEpochException.class);
        }

        assertThat(sent).containsExactly("g1", "stop", "asyncStop");
    }

    @Test
    void theOwnerOfAGroupCannotBypassIt() {
        var scheduler = scheduler(GENEROUS);

        try (var group = scheduler.openGroup(scheduler.stopEpoch())) {
            assertThatThrownBy(() -> scheduler.call(DriveLane.STATUS, "outside", tx("outside", 1)))
                    .isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> scheduler.openGroup(group.expectedEpoch()))
                    .isInstanceOf(IllegalStateException.class);
        }
        assertThat(sent).isEmpty();
    }

    @Test
    void aGroupThatSubmitsNothingForTheControlDeadlineIsAbandoned() {
        var scheduler = scheduler(LaneDeadlines.of(Duration.ofMillis(SLOW), Duration.ofSeconds(2),
                Duration.ofSeconds(2), Duration.ofSeconds(2)));
        var group = scheduler.openGroup(scheduler.stopEpoch());
        var outside = Thread.ofPlatform().start(() -> scheduler.call(DriveLane.STATUS, "outside", tx("outside", 1)));

        join(outside);
        assertThat(sent).containsExactly("outside");
        assertThatThrownBy(() -> scheduler.callLinked(group, DriveLane.CONTROL, "late", tx("late", 1)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("abandoned");
        group.close();
    }

    // ---- completions and the I/O thread ------------------------------------------------------

    @Test
    void completionsNeverRunOnTheIoThread() {
        var scheduler = scheduler(GENEROUS);
        var seenIn = new AtomicReference<String>();
        var seenOut = new AtomicReference<String>();

        scheduler.submit(DriveLane.STATUS, "read", () -> {
            seenIn.set(Thread.currentThread().getName());
            Thread.sleep(SLOW);
            return 1;
        }).thenApply(v -> {
            seenOut.set(Thread.currentThread().getName());
            return v;
        }).join();

        assertThat(seenIn.get()).isEqualTo("test-io");
        assertThat(seenOut.get()).isNotEqualTo("test-io");
    }

    @Test
    void aSyncCallFromTheIoThreadIsRefusedWithoutDeadlock() {
        var scheduler = scheduler(GENEROUS);

        assertThatThrownBy(() -> scheduler.call(DriveLane.STATUS, "callsBack",
                () -> scheduler.call(DriveLane.STATUS, "inner", tx("inner", 1))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("own I/O thread");
        assertThat(sent).isEmpty();
    }

    @Test
    void aSyncCallIgnoresAnInterruptAndKeepsIt() {
        var scheduler = scheduler(GENEROUS);

        Thread.currentThread().interrupt();
        try {
            assertThat(scheduler.call(DriveLane.STOP, "stop", tx("stop", 20))).isEqualTo("stop");
            assertThat(Thread.currentThread().isInterrupted()).as("interrupt kept").isTrue();
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void aFailureKeepsItsOwnType() {
        var scheduler = scheduler(GENEROUS);

        assertThatThrownBy(() -> scheduler.call(DriveLane.SETPOINT, "refused", () -> {
            throw new IllegalArgumentException("ramp too long");
        })).isExactlyInstanceOf(IllegalArgumentException.class).hasMessage("ramp too long");
        var future = scheduler.submit(DriveLane.SETPOINT, "refused", () -> {
            throw new IllegalArgumentException("ramp too long");
        });
        assertThatThrownBy(future::join).isInstanceOf(CompletionException.class)
                .hasCauseExactlyInstanceOf(IllegalArgumentException.class);
    }

    // ---- close ------------------------------------------------------------------------------

    @Test
    void closeWaitsForTheTransactionInFlightSendsQueuedStopsAndFailsTheRest() {
        var scheduler = scheduler(GENEROUS);
        var inFlight = scheduler.submit(DriveLane.STATUS, "inFlight", tx("inFlight", SLOW));
        sleep(SLOW / 3);
        var queued = scheduler.submit(DriveLane.STATUS, "queued", tx("queued", 1));
        var queuedStop = scheduler.submit(DriveLane.STOP, "queuedStop", tx("queuedStop", 1));

        long started = System.nanoTime();
        scheduler.close();

        assertThat(scheduler.isTerminated()).as("nothing in flight once close returns").isTrue();
        assertThat(millisSince(started)).isLessThanOrEqualTo(SLOW + SLACK);
        assertThat(inFlight.join()).isEqualTo("inFlight");
        assertThat(sent).as("a stop queued at close still goes out").containsExactly("inFlight", "queuedStop");
        assertThat(queuedStop.join()).isEqualTo("queuedStop");
        assertThatThrownBy(queued::join).hasCauseInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> scheduler.submit(DriveLane.STOP, "late", tx("late", 1)))
                .isInstanceOf(IllegalStateException.class);

        long again = System.nanoTime();
        scheduler.close();
        assertThat(millisSince(again)).as("idempotent").isLessThan(SLOW);
    }

    /**
     * A wedged transfer cannot be aborted, so close() gives up at the STOP deadline and says so
     * rather than return with it in flight: a caller that opened a fresh handle then would put a
     * second session on the drive. The I/O thread finishes the close by itself: the stop whose
     * caller already got the deadline failure goes out, then the last act closes the port.
     */
    @Test
    void closeThrowsPendingAtTheStopDeadlineWhileATransferIsWedgedAndTheIoThreadFinishesTheClose() {
        var lastActThreads = new CopyOnWriteArrayList<String>();
        var scheduler = scheduler(new LaneDeadlines(Duration.ofMillis(SLOW), Duration.ofMillis(SLOW),
                Duration.ofMillis(SLOW), Duration.ofMillis(SLOW), Duration.ofMillis(SLOW)),
                () -> lastActThreads.add(Thread.currentThread().getName()));
        var wedge = scheduler.submit(DriveLane.STATUS, "wedged", tx("wedged", 8 * SLOW));
        sleep(SLOW / 3);
        assertThatThrownBy(() -> scheduler.call(DriveLane.STOP, "stop", tx("stop", 1)))
                .isInstanceOf(DriveDeadlineException.class);

        long started = System.nanoTime();
        assertThatThrownBy(scheduler::close).isInstanceOf(DriveClosePendingException.class)
                .hasMessageContaining("still open");
        assertThat(millisSince(started)).as("bounded by the STOP deadline").isLessThanOrEqualTo(SLOW + SLACK);
        assertThat(scheduler.isTerminated()).as("the wedge is still in flight").isFalse();
        assertThat(lastActThreads).as("the port is still open").isEmpty();
        assertThatThrownBy(() -> scheduler.submit(DriveLane.STOP, "late", tx("late", 1)))
                .as("closed to callers all the same").isInstanceOf(IllegalStateException.class);

        awaitTerminated(scheduler);
        assertThat(sent).containsExactly("wedged", "stop");
        assertThat(lastActThreads).as("the port closed once, on the I/O thread").containsExactly("test-io");
        assertThatThrownBy(wedge::join).as("its caller had the deadline failure")
                .hasCauseInstanceOf(DriveDeadlineException.class);

        long again = System.nanoTime();
        scheduler.close();
        assertThat(millisSince(again)).as("a later close returns at once").isLessThan(SLOW);
        assertThat(lastActThreads).hasSize(1);
    }

    @Test
    void closeOnAnIdleSchedulerRunsTheLastActOnTheIoThreadBeforeReturning() {
        var lastActThreads = new CopyOnWriteArrayList<String>();
        var scheduler = scheduler(GENEROUS, () -> lastActThreads.add(Thread.currentThread().getName()));

        scheduler.close();

        assertThat(scheduler.isTerminated()).isTrue();
        assertThat(lastActThreads).containsExactly("test-io");
        scheduler.close();
        assertThat(lastActThreads).as("once").hasSize(1);
    }

    /**
     * The port close runs on the I/O thread, so its failure is handed to a closer: the first one to
     * see the I/O thread ended - here the second, since the first gave up at the STOP deadline.
     */
    @Test
    void aLastActFailureIsRethrownToTheFirstCloserThatSeesTheIoThreadEnded() {
        var scheduler = scheduler(new LaneDeadlines(Duration.ofMillis(SLOW), Duration.ofSeconds(2),
                Duration.ofSeconds(2), Duration.ofSeconds(2), Duration.ofSeconds(2)), () -> {
            throw new IllegalStateException("port close failed");
        });
        scheduler.submit(DriveLane.STATUS, "wedged", tx("wedged", 3 * SLOW));
        sleep(SLOW / 3);

        assertThatThrownBy(scheduler::close).isInstanceOf(DriveClosePendingException.class);
        awaitTerminated(scheduler);
        assertThatThrownBy(scheduler::close).isExactlyInstanceOf(IllegalStateException.class)
                .hasMessage("port close failed");
        assertThatCode(scheduler::close).as("reported once").doesNotThrowAnyException();
    }

    @Test
    void aCheckedLastActFailureReachesTheCloserAsADriveCommunicationException() {
        var scheduler = scheduler(GENEROUS, () -> sneakyThrow(new Exception("negative confirmation")));

        assertThatThrownBy(scheduler::close).isExactlyInstanceOf(DriveCommunicationException.class)
                .hasRootCauseMessage("negative confirmation");
    }

    @Test
    void aStopThatLosesTheRaceWithCloseFailsInsteadOfHanging() {
        var scheduler = scheduler(GENEROUS);
        scheduler.afterStopEpochBump(() -> join(Thread.ofPlatform().start(scheduler::close)));

        var stop = scheduler.submit(DriveLane.STOP, "stop", tx("stop", 1));

        assertThatThrownBy(stop::join).hasCauseInstanceOf(IllegalStateException.class);
        assertThat(sent).isEmpty();
    }

    @Test
    void closeOnTheIoThreadIsRefused() {
        var scheduler = scheduler(GENEROUS);

        assertThatThrownBy(() -> scheduler.call(DriveLane.STATUS, "closesItself", () -> {
            scheduler.close();
            return 0;
        })).isInstanceOf(IllegalStateException.class).hasMessageContaining("own I/O thread");
        assertThat(scheduler.isTerminated()).isFalse();
    }

    @Test
    void anErrorInOneTransactionFailsOnlyItsFuture() {
        var scheduler = scheduler(GENEROUS);

        var broken = scheduler.submit(DriveLane.STATUS, "broken", () -> {
            throw new AssertionError("native fault");
        });

        assertThatThrownBy(broken::join).hasCauseInstanceOf(AssertionError.class);
        assertThat(scheduler.call(DriveLane.STATUS, "next", tx("next", 1))).isEqualTo("next");
    }

    // ---- helpers ----------------------------------------------------------------------------

    private static void assertStale(CompletableFuture<?> future) {
        assertThatThrownBy(future::join).isInstanceOf(CompletionException.class)
                .hasCauseInstanceOf(StaleStopEpochException.class);
    }

    /** The group's owner may not submit outside it, so another thread does. */
    private static <T> T fromAnotherThread(Supplier<T> submit) {
        var result = new AtomicReference<T>();
        join(Thread.ofPlatform().start(() -> result.set(submit.get())));
        return result.get();
    }

    private static boolean await(CountDownLatch latch) {
        try {
            return latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static void join(Thread thread) {
        try {
            thread.join(Duration.ofSeconds(5));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        assertThat(thread.isAlive()).as(thread.getName() + " finished").isFalse();
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
