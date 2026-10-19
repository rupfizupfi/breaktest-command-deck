package ch.rupfizupfi.deck.testrunner;

import java.util.List;

/**
 * The recovery parameters one run actually ran under, mirroring {@link RecoveryProperties}
 * field for field. Jackson serializes this into the run's audit record.
 * <p>
 * Snapshotted once at run start and immutable, because devtools can refresh the live bean mid-run.
 * Field docs live on {@link RecoveryProperties}.
 */
public record RecoveryGates(long reconnectWindowMillis, List<Long> backoffMillis,
                            long safeHoldTimeoutMillis, long maxHoldForResumeMillis,
                            long plausibilityGateMillis, double driftFraction, int maxLossesPerRun,
                            double minEnvelopeNewton, boolean resumeEnabled,
                            long maxSampleIntervalMillis, int plausibilityGateMinSamples,
                            long maxFrozenMillis) {

    /**
     * The measurement loop's no-data timeout. Fixed, not a gate: it is sized above the driver's
     * 80 ms drop-and-continue budget. Kept here so {@link RecoveryProperties} can bound the
     * sample-interval gate below it while dependencies keep pointing down.
     */
    public static final long NO_DATA_TIMEOUT_MS = 250;

    public RecoveryGates {
        // The bound list stays mutable on the properties bean.
        backoffMillis = backoffMillis == null ? List.of() : List.copyOf(backoffMillis);
    }
}
