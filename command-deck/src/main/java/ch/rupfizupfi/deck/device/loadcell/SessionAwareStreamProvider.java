package ch.rupfizupfi.deck.device.loadcell;

import ch.rupfizupfi.deck.device.api.LoadCellStream;
import ch.rupfizupfi.deck.device.api.LoadCellStreamProvider;

/**
 * Deck-side seam that lets a stream provider tell a mid-run reconnect from a run's first open, and
 * the only provider type {@link LoadCellDevice} takes.
 * <p>
 * Not part of {@code device-api}, the published driver contract: only the simulator needs the flag,
 * so a reconnect does not mount a fresh specimen. A driver's plain {@link LoadCellStreamProvider}
 * is adapted by {@code DeviceService}, which ignores the flag for it.
 */
@FunctionalInterface
public interface SessionAwareStreamProvider {

    /** @param reconnect true when re-opening mid-run, false when a run is starting */
    LoadCellStream open(boolean reconnect);
}
