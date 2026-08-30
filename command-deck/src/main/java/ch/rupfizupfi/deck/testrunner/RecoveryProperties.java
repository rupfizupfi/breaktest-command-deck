package ch.rupfizupfi.deck.testrunner;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

import java.util.List;

/**
 * Timing and gating parameters for load-cell loss recovery, all under
 * {@code deck.testrunner.recovery}. Defaults live here, not in {@code application*.properties} —
 * same arrangement as {@code deck.simulated}.
 * <p>
 * Every duration is a primitive {@code long} of milliseconds rather than a {@link java.time.Duration}
 * because {@link #snapshot()} is copied verbatim into the run's audit record: {@code PT10S} and
 * {@code PT0.010S} are not comparable across runs by a script, two integers are.
 * <p>
 * Bound values are only ever as good as the ones a run actually used — see {@link RecoveryGates}
 * for why the run snapshots them instead of reading this bean.
 */
@Component
@ConfigurationProperties("deck.testrunner.recovery")
@Validated
public class RecoveryProperties {

    /** Total time the reconnector keeps retrying before the loss becomes unrecoverable. */
    @Min(1000)
    private long reconnectWindowMillis = 10_000;

    /** Delay before each reconnect attempt; the last entry repeats once the list is exhausted. */
    @NotEmpty
    private List<Long> backoffMillis = List.of(1000L, 2000L, 4000L);

    /** How long a SAFE_HOLD may stand before the server auto-aborts it without any UI involvement. */
    @Min(1000)
    private long safeHoldTimeoutMillis = 60_000;

    /**
     * Hold duration past which Resume is refused even though the hold itself is still alive: the
     * specimen creeps and relaxes under a held load, so a resumed curve stops being the same test.
     */
    @Min(0)
    private long maxHoldForResumeMillis = 30_000;

    /** How long a reconnected stream must keep delivering plausible samples before Resume is offered. */
    @Min(20)
    private long plausibilityGateMillis = 100;

    /** Drift gate width as a fraction of the test's force envelope — see {@link #minEnvelopeNewton}. */
    @DecimalMin("0.0")
    @DecimalMax("1.0")
    private double driftFraction = 0.02;

    /** Losses tolerated in one run; the next one aborts instead of offering another recovery. */
    @Min(0)
    private int maxLossesPerRun = 2;

    /**
     * Floor for the drift gate. The gate is {@code |F - lastKnownForce| < driftFraction * envelope}
     * with {@code envelope = max(|upperLimit|, |lowerLimit|)}, so a test configured with near-zero
     * limits would get a gate of a few newton that no real sensor can pass, and every reconnect
     * would be rejected as implausible. The envelope used for the gate is therefore never below this.
     */
    @DecimalMin("1.0")
    private double minEnvelopeNewton = 1000;

    /** Kill switch: false means a sensor loss can only ever end in an abort. */
    private boolean resumeEnabled = true;

    /**
     * The UI must not offer Resume on a run the server has already auto-aborted. Both timers start
     * at the same instant, so a resume window longer than the hold is unreachable by construction.
     */
    @AssertTrue(message = "deck.testrunner.recovery.maxHoldForResumeMillis must not exceed "
            + "safeHoldTimeoutMillis, otherwise Resume is offered for a run that already auto-aborted")
    public boolean isResumeWindowInsideHold() {
        return maxHoldForResumeMillis <= safeHoldTimeoutMillis;
    }

    /**
     * A first backoff at or beyond the reconnect window means the window expires before a single
     * attempt is made, which reads in the log as "reconnect failed" for a sensor nobody ever retried.
     */
    @AssertTrue(message = "deck.testrunner.recovery.backoffMillis entries must all be positive and "
            + "the first one must be shorter than reconnectWindowMillis, otherwise the window "
            + "expires before the first reconnect attempt is made")
    public boolean isBackoffInsideWindow() {
        // An empty or absent list is @NotEmpty's finding to report; duplicating it here would give
        // the operator two messages for one mistake.
        if (backoffMillis == null || backoffMillis.isEmpty()) {
            return true;
        }
        for (Long delay : backoffMillis) {
            if (delay == null || delay <= 0) {
                return false;
            }
        }
        return backoffMillis.getFirst() < reconnectWindowMillis;
    }

    /** The immutable copy a run holds for its whole lifetime. */
    public RecoveryGates snapshot() {
        return new RecoveryGates(reconnectWindowMillis, backoffMillis, safeHoldTimeoutMillis,
                maxHoldForResumeMillis, plausibilityGateMillis, driftFraction, maxLossesPerRun,
                minEnvelopeNewton, resumeEnabled);
    }

    public long getReconnectWindowMillis() {
        return reconnectWindowMillis;
    }

    public void setReconnectWindowMillis(long reconnectWindowMillis) {
        this.reconnectWindowMillis = reconnectWindowMillis;
    }

    public List<Long> getBackoffMillis() {
        return backoffMillis;
    }

    public void setBackoffMillis(List<Long> backoffMillis) {
        this.backoffMillis = backoffMillis;
    }

    public long getSafeHoldTimeoutMillis() {
        return safeHoldTimeoutMillis;
    }

    public void setSafeHoldTimeoutMillis(long safeHoldTimeoutMillis) {
        this.safeHoldTimeoutMillis = safeHoldTimeoutMillis;
    }

    public long getMaxHoldForResumeMillis() {
        return maxHoldForResumeMillis;
    }

    public void setMaxHoldForResumeMillis(long maxHoldForResumeMillis) {
        this.maxHoldForResumeMillis = maxHoldForResumeMillis;
    }

    public long getPlausibilityGateMillis() {
        return plausibilityGateMillis;
    }

    public void setPlausibilityGateMillis(long plausibilityGateMillis) {
        this.plausibilityGateMillis = plausibilityGateMillis;
    }

    public double getDriftFraction() {
        return driftFraction;
    }

    public void setDriftFraction(double driftFraction) {
        this.driftFraction = driftFraction;
    }

    public int getMaxLossesPerRun() {
        return maxLossesPerRun;
    }

    public void setMaxLossesPerRun(int maxLossesPerRun) {
        this.maxLossesPerRun = maxLossesPerRun;
    }

    public double getMinEnvelopeNewton() {
        return minEnvelopeNewton;
    }

    public void setMinEnvelopeNewton(double minEnvelopeNewton) {
        this.minEnvelopeNewton = minEnvelopeNewton;
    }

    public boolean isResumeEnabled() {
        return resumeEnabled;
    }

    public void setResumeEnabled(boolean resumeEnabled) {
        this.resumeEnabled = resumeEnabled;
    }
}
