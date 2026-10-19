package ch.rupfizupfi.deck.device.api;

/**
 * One force sample.
 *
 * @param force     newtons
 * @param timestamp epoch millis, not nanoTime: {@code ForceBroadcaster} compares it against
 *                  {@code System.currentTimeMillis()} to decide when to flush a batch
 */
public record Measurement(float force, long timestamp) {
    // The JSON keys force/timestamp are /topic/load-cell wire contract, parsed untyped by
    // loadCellBatch.ts; renaming a component breaks the live chart without a type error.
}
