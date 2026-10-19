package ch.rupfizupfi.deck.device.api;

/**
 * Who serializes calls on a {@link Drive} handle, as {@link Drive#gating()} answers it. Since
 * contract 1.5.0.
 */
public enum DriveGating {

    /**
     * The handle is not thread-safe: the caller must never have two calls in flight on it, and a
     * stop waits behind whatever call holds the caller's lock. Every plugin built before 1.5.0
     * answers this through the default; {@link Drive#stopEpoch()}, {@link Drive#runLinked} and
     * {@link Drive#async()} throw on such a handle.
     */
    CALLER_SERIALIZES,

    /**
     * The provider gates calls itself, so any thread may call at any time. A stop-class call is
     * never blocked by another call for longer than the one bus transaction already in flight, the
     * handle keeps a {@linkplain Drive#stopEpoch() stop epoch}, runs {@linkplain Drive#runLinked
     * linked groups} and offers the {@linkplain Drive#async() async view}. Every call carries its
     * {@link DriveLane}'s deadline and fails with {@link DriveDeadlineException} once it passes.
     */
    DRIVER_SERIALIZES
}
