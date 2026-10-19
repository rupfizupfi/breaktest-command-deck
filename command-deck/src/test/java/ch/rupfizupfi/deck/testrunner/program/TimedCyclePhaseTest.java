package ch.rupfizupfi.deck.testrunner.program;

import ch.rupfizupfi.deck.data.TestParameter;
import ch.rupfizupfi.deck.testrunner.run.Direction;
import ch.rupfizupfi.deck.testrunner.run.FinishCause;
import ch.rupfizupfi.deck.testrunner.run.Limit;
import ch.rupfizupfi.deck.testrunner.run.RunEvent;
import ch.rupfizupfi.deck.testrunner.run.Verdict;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The time-cyclic run phase: the opposite turn scheduled at the measured delay, the cyclic verdict,
 * and {@link TimedCyclePhase#close()} as the end of everything the phase scheduled - including a
 * task already past its delay (I17).
 */
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class TimedCyclePhaseTest {

    private static final long RELEASE_MS = 150;
    private static final long PULL_MS = 400;

    private final BlockingQueue<RunEvent> sent = new LinkedBlockingQueue<>();
    private TimedCyclePhase phase;

    private CyclicTurns turns(int cycles) {
        TestParameter parameter = new TestParameter();
        parameter.speed = 50;
        parameter.upperTurnForce = 1.0;
        parameter.lowerTurnForce = 0.1;
        parameter.upperShutOffThreshold = 3.0;
        parameter.cycleCount = cycles;
        return new CyclicTurns(parameter);
    }

    private FakeRunControl control(CyclicTurns turns, Direction heading) {
        FakeRunControl control = new FakeRunControl(turns.initialLimits());
        control.direction = heading;
        control.max = 1000f;
        control.min = 100f;
        return control;
    }

    @AfterEach
    void closePhase() {
        if (phase != null) {
            phase.close();
        }
    }

    @Test
    void aReleaseIsFollowedByAPullAfterTheReleaseTime() throws InterruptedException {
        CyclicTurns turns = turns(10);
        phase = new TimedCyclePhase(turns, sent::add, RELEASE_MS, PULL_MS, Verdict.CONTINUE);
        FakeRunControl control = control(turns, Direction.PULL);

        long start = System.nanoTime();
        assertThat(phase.onLimit(Limit.UPPER, control)).isSameAs(phase);

        assertThat(control.calls).contains("turn RELEASE");
        assertThat(sent.poll(5, TimeUnit.SECONDS)).isEqualTo(new RunEvent.TimedTurn(Direction.PULL));
        assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)).isGreaterThanOrEqualTo(RELEASE_MS);
    }

    @Test
    void aPullIsFollowedByAReleaseAfterThePullTime() throws InterruptedException {
        CyclicTurns turns = turns(10);
        phase = new TimedCyclePhase(turns, sent::add, RELEASE_MS, PULL_MS, Verdict.CONTINUE);
        FakeRunControl control = control(turns, Direction.RELEASE);

        long start = System.nanoTime();
        phase.onTimedTurn(Direction.PULL, control);

        assertThat(control.calls).contains("turn PULL");
        assertThat(sent.poll(5, TimeUnit.SECONDS)).isEqualTo(new RunEvent.TimedTurn(Direction.RELEASE));
        assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)).isGreaterThanOrEqualTo(PULL_MS);
    }

    @Test
    void theVerdictIsTheCyclicTurnsAndTheHandoverVerdictStandsUntilTheFirstTurn() {
        CyclicTurns turns = turns(1);
        phase = new TimedCyclePhase(turns, sent::add, RELEASE_MS, PULL_MS, Verdict.CONTINUE);
        assertThat(phase.verdict()).isEqualTo(Verdict.CONTINUE);

        phase.onLimit(Limit.LOWER, control(turns, Direction.RELEASE));

        assertThat(phase.verdict()).isEqualTo(Verdict.finish(FinishCause.CYCLES_DONE));
    }

    @Test
    void closeCancelsThePendingTurn() throws InterruptedException {
        CyclicTurns turns = turns(10);
        phase = new TimedCyclePhase(turns, sent::add, RELEASE_MS, PULL_MS, Verdict.CONTINUE);
        phase.onLimit(Limit.UPPER, control(turns, Direction.PULL));

        phase.close();

        assertThat(phase.isClosed()).isTrue();
        assertThat(sent.poll(RELEASE_MS * 3, TimeUnit.MILLISECONDS)).isNull();
    }

    /**
     * The guarantee the hold entry relies on: a task already posting when the phase is closed has
     * finished posting by the time close() returns, so nothing it sends can land after the hold.
     */
    @Test
    void closeReturnsOnlyAfterATaskInFlightHasPosted() throws InterruptedException {
        CountDownLatch posting = new CountDownLatch(1);
        AtomicBoolean posted = new AtomicBoolean();
        CyclicTurns turns = turns(10);
        phase = new TimedCyclePhase(turns, event -> {
            posting.countDown();
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                // shutdownNow interrupts the sleep; the post itself still completes, as an offer would.
            }
            posted.set(true);
        }, 0, 0, Verdict.CONTINUE);
        phase.onLimit(Limit.UPPER, control(turns, Direction.PULL));
        assertThat(posting.await(5, TimeUnit.SECONDS)).isTrue();

        phase.close();

        assertThat(posted).isTrue();
        assertThat(phase.isClosed()).isTrue();
    }

    @Test
    void aTurnAfterCloseSchedulesNothingAndDoesNotThrow() throws InterruptedException {
        CyclicTurns turns = turns(10);
        phase = new TimedCyclePhase(turns, sent::add, 0, 0, Verdict.CONTINUE);
        phase.close();

        phase.onLimit(Limit.UPPER, control(turns, Direction.PULL));

        assertThat(sent.poll(200, TimeUnit.MILLISECONDS)).isNull();
    }
}
