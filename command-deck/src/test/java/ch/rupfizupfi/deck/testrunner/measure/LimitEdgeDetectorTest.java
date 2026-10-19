package ch.rupfizupfi.deck.testrunner.measure;

import ch.rupfizupfi.deck.testrunner.run.Direction;
import ch.rupfizupfi.deck.testrunner.run.Limit;
import ch.rupfizupfi.deck.testrunner.run.RunEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** {@link LimitEdgeDetector} on its own, against a 100 N / -100 N context. Single-threaded. */
class LimitEdgeDetectorTest {

    private static final String UPPER = "UPPER";
    private static final String LOWER = "LOWER";

    /** Every event the detector let into the run, in order, so a repeat it let through would show. */
    private final List<RunEvent> sent = new ArrayList<>();
    private ForceLimits context;
    private LimitEdgeDetector edge;

    @BeforeEach
    void attach() {
        context = new ForceLimits(100.0, -100.0, Double.POSITIVE_INFINITY);
        edge = new LimitEdgeDetector(context, sent::add);
    }

    /** One drain, as the measurement loop feeds it: every sample observed, then one emit. */
    private void drain(float... forces) {
        for (float force : forces) {
            edge.observe(force);
        }
        edge.emit();
    }

    /** The crossings sent, by limit, in order. */
    private List<String> crossings() {
        return sent.stream().map(e -> ((RunEvent.LimitCrossed) e).limit().name()).toList();
    }

    @Test
    void aForceThatStaysAboveTheUpperLimitIsOneCrossing() {
        for (int i = 0; i < 10; i++) {
            drain(150f, 160f);
        }

        assertThat(crossings()).containsExactly(UPPER);
    }

    @Test
    void theCrossingCarriesTheSampleThatDecidedIt() {
        drain(50f, 150f, 160f);

        assertThat(sent).containsExactly(new RunEvent.LimitCrossed(Limit.UPPER, 150f));
    }

    @Test
    void theOppositeEdgeReArmsTheCrossing() {
        drain(150f);
        drain(50f);
        drain(-150f);
        drain(-160f);
        drain(150f);

        assertThat(crossings()).containsExactly(UPPER, LOWER, UPPER);
    }

    @Test
    void returningInsideTheLimitsAloneDoesNotReArm() {
        // An edge of the force against the other limit, not a dip back inside, re-arms: the same
        // rule as the int repeat filter it replaces.
        drain(150f);
        drain(50f);
        drain(150f);

        assertThat(crossings()).containsExactly(UPPER);
    }

    @Test
    void resetReArmsBothEdges() {
        // The RunLoop's RESUMING entry action resets before a resumed run's first crossing.
        drain(150f);
        edge.reset();
        drain(150f);

        assertThat(crossings()).containsExactly(UPPER, UPPER);
    }

    @Test
    void aNonFiniteSampleNeverEmits() {
        drain(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY);

        assertThat(sent).isEmpty();
    }

    @Test
    void aNonFiniteSampleDoesNotRetireTheDrainsDecision() {
        drain(Float.POSITIVE_INFINITY, 50f, -150f);

        assertThat(crossings()).containsExactly(LOWER);
    }

    @Test
    void theFirstOutOfRangeSampleDecidesTheDrain() {
        drain(-150f, 150f);

        assertThat(crossings()).containsExactly(LOWER);
    }

    @Test
    void aDiscardedDrainSendsNothingAndLeavesTheEdgeArmed() {
        // The loop discards a drain that ended in a ceiling trip, a sensor fault or an open gate.
        edge.observe(150f);
        edge.discard();
        assertThat(sent).isEmpty();

        drain(150f);
        assertThat(crossings()).containsExactly(UPPER);
    }

    @Test
    void anAdmittedTimedPullReArmsTheUpperEdge() {
        // TimedCyclePhase's turns enter through admit(): a timed PULL must re-arm the
        // upper edge, or the upper limit's reversal would be lost after the first timed turn.
        drain(150f);
        assertThat(edge.admit(Direction.PULL)).isTrue();
        drain(150f);

        assertThat(crossings()).containsExactly(UPPER, UPPER);
    }

    @Test
    void aTimedTurnRepeatingTheLastEdgeIsRefused() {
        // A crossing and a timed turn are one edge when they agree: the second would make the
        // TimedCyclePhase schedule its opposite twice.
        drain(-150f);

        assertThat(edge.admit(Direction.PULL)).isFalse();
        assertThat(edge.admit(Direction.RELEASE)).isTrue();
        assertThat(edge.admit(Direction.RELEASE)).isFalse();
    }

    @Test
    void anAdmittedTimedTurnSuppressesTheCrossingItAgreesWith() {
        assertThat(edge.admit(Direction.RELEASE)).isTrue();
        drain(150f);

        assertThat(sent).isEmpty();
    }

    @Test
    void theLimitsAreReadPerSampleSoAMovedLimitDecides() {
        // Cyclic programs move the limits every cycle; the detector holds none of its own.
        drain(90f);
        context.adapt(80.0, context.getLowerLimit());
        drain(90f);

        assertThat(crossings()).containsExactly(UPPER);
    }
}
