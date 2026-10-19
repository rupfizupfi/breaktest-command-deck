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
 * {@code deck.testrunner.recovery}. Defaults live here, not in {@code application*.properties}.
 * <p>
 * Durations are {@code long} milliseconds rather than {@link java.time.Duration} because
 * {@link #snapshot()} lands in the audit record, where integers compare across runs.
 * <p>
 * Runs use a snapshot, not this bean; see {@link RecoveryGates}.
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

    /** How long a SAFE_HOLD may stand before the server auto-aborts it. */
    @Min(1000)
    private long safeHoldTimeoutMillis = 60_000;

    /**
     * Hold duration past which Resume is refused: the specimen creeps under a held load.
     */
    @Min(0)
    private long maxHoldForResumeMillis = 30_000;

    /** How long a reconnected stream must keep delivering plausible samples before Resume is offered. */
    @Min(20)
    private long plausibilityGateMillis = 100;

    /**
     * Plausible samples the gate's unbroken run must hold on top of {@link #plausibilityGateMillis};
     * a failed sample resets both. A stream too slow to deliver them before the gate's deadline
     * fails the gate. Two is the least that gives a run any length.
     */
    @Min(2)
    private int plausibilityGateMinSamples = 10;

    /** Drift gate width as a fraction of the test's force envelope — see {@link #minEnvelopeNewton}. */
    @DecimalMin("0.0")
    @DecimalMax("1.0")
    private double driftFraction = 0.02;

    /** Losses tolerated in one run; the next one aborts instead of offering another recovery. */
    @Min(0)
    private int maxLossesPerRun = 2;

    /**
     * Floor for the drift gate's envelope. The gate is
     * {@code |F - lastKnownForce| < driftFraction * max(|upperLimit|, |lowerLimit|)}, which near-zero
     * limits would shrink below what a real sensor can pass.
     */
    @DecimalMin("1.0")
    private double minEnvelopeNewton = 1000;

    /** Kill switch: false means a sensor loss can only ever end in an abort. */
    private boolean resumeEnabled = true;

    /**
     * Longest mean time per sample a drain may show: the time since the previous drain divided by
     * the samples it carried. Sparser drains vote, and 3 of the last 5 voting is a sensor loss. A
     * conservative first value, uncalibrated against the bench; see {@code safety-and-recovery.md}.
     */
    @Min(1)
    private long maxSampleIntervalMillis = 100;

    /**
     * Longest a reading may stay bit-identical, in sample time, before the frozen detector trips,
     * on top of its 100-sample count, so a slow feed trips in time rather than after 100 of its
     * intervals. Not below the no-data timeout: a stuck reading is no stricter a fault than none.
     * Uncalibrated; see {@code safety-and-recovery.md}.
     */
    @Min(RecoveryGates.NO_DATA_TIMEOUT_MS)
    private long maxFrozenMillis = 1000;

    /**
     * Both timers start together, so a resume window longer than the hold would offer Resume on an
     * auto-aborted run.
     */
    @AssertTrue(message = "deck.testrunner.recovery.maxHoldForResumeMillis must not exceed "
            + "safeHoldTimeoutMillis, otherwise Resume is offered for a run that already auto-aborted")
    public boolean isResumeWindowInsideHold() {
        return maxHoldForResumeMillis <= safeHoldTimeoutMillis;
    }

    /**
     * A first backoff at or beyond the reconnect window means no attempt is ever made.
     */
    @AssertTrue(message = "deck.testrunner.recovery.backoffMillis entries must all be positive and "
            + "the first one must be shorter than reconnectWindowMillis, otherwise the window "
            + "expires before the first reconnect attempt is made")
    public boolean isBackoffInsideWindow() {
        // Left to @NotEmpty.
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

    /**
     * A bound at or past the no-data timeout can never vote before that detector has already
     * tripped, so it would read as a fourth detector and be none.
     */
    @AssertTrue(message = "deck.testrunner.recovery.maxSampleIntervalMillis must be shorter than the "
            + RecoveryGates.NO_DATA_TIMEOUT_MS + " ms no-data timeout, otherwise a feed slow enough "
            + "to break it is already silence")
    public boolean isSampleIntervalBelowNoDataTimeout() {
        return maxSampleIntervalMillis < RecoveryGates.NO_DATA_TIMEOUT_MS;
    }

    /** The immutable copy a run holds for its whole lifetime. */
    public RecoveryGates snapshot() {
        return new RecoveryGates(reconnectWindowMillis, backoffMillis, safeHoldTimeoutMillis,
                maxHoldForResumeMillis, plausibilityGateMillis, driftFraction, maxLossesPerRun,
                minEnvelopeNewton, resumeEnabled, maxSampleIntervalMillis, plausibilityGateMinSamples,
                maxFrozenMillis);
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

    public long getMaxSampleIntervalMillis() {
        return maxSampleIntervalMillis;
    }

    public void setMaxSampleIntervalMillis(long maxSampleIntervalMillis) {
        this.maxSampleIntervalMillis = maxSampleIntervalMillis;
    }

    public int getPlausibilityGateMinSamples() {
        return plausibilityGateMinSamples;
    }

    public void setPlausibilityGateMinSamples(int plausibilityGateMinSamples) {
        this.plausibilityGateMinSamples = plausibilityGateMinSamples;
    }

    public long getMaxFrozenMillis() {
        return maxFrozenMillis;
    }

    public void setMaxFrozenMillis(long maxFrozenMillis) {
        this.maxFrozenMillis = maxFrozenMillis;
    }
}
