package ch.rupfizupfi.deck.device.api;

/**
 * One force sample.
 *
 * @param force     newtons
 * @param timestamp epoch millis, NOT nanoTime: {@code ForceBroadcaster} compares it against
 *                  {@code System.currentTimeMillis()} to decide when to flush a batch
 */
public record Measurement(float force, long timestamp) {
    // The JSON keys force/timestamp are part of the /topic/load-cell contract, consumed by an
    // untyped rxStomp.watch() in StatusService.ts that no typecheck can see. Renaming either
    // component silently breaks the live chart.
}
