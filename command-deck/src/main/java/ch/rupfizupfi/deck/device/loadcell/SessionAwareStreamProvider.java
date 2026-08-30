package ch.rupfizupfi.deck.device.loadcell;

import ch.rupfizupfi.deck.device.api.LoadCellStream;

/**
 * Deck-side seam that lets a stream provider tell a mid-run reconnect from a run's first open.
 * <p>
 * Deliberately NOT part of {@code device-api}: that is a published contract the {@code dscusb} and
 * {@code usbmodbus} repos compile against, while this distinction exists only to keep the simulator
 * honest - a reconnect must not mount a fresh specimen. A real driver opens the same handle either
 * way, so {@link LoadCellDevice} uses this only when the injected provider implements it and falls
 * back to {@link ch.rupfizupfi.deck.device.api.LoadCellStreamProvider#open()} otherwise.
 */
public interface SessionAwareStreamProvider {

    /** @param reconnect true when re-opening mid-run, false when a run is starting */
    LoadCellStream open(boolean reconnect);
}
