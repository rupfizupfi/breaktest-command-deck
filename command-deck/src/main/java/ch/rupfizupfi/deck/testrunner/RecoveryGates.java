package ch.rupfizupfi.deck.testrunner;

import java.util.List;

/**
 * The recovery parameters one run actually ran under, mirroring {@link RecoveryProperties}
 * field for field. Jackson serializes this into the run's audit record.
 * <p>
 * Snapshotted ONCE at run start and immutable from then on. A run must never read the live
 * properties bean: devtools is active in dev and a mid-run refresh would leave the audit log
 * claiming gates that differ from the ones the run's decisions were actually made with.
 * Field docs live on {@link RecoveryProperties}; this is the copy, not the definition.
 */
public record RecoveryGates(long reconnectWindowMillis, List<Long> backoffMillis,
                            long safeHoldTimeoutMillis, long maxHoldForResumeMillis,
                            long plausibilityGateMillis, double driftFraction, int maxLossesPerRun,
                            double minEnvelopeNewton, boolean resumeEnabled) {

    public RecoveryGates {
        // The bound list belongs to the properties bean and stays mutable there; without the copy
        // "immutable snapshot" would only hold for the primitives.
        backoffMillis = backoffMillis == null ? List.of() : List.copyOf(backoffMillis);
    }
}
