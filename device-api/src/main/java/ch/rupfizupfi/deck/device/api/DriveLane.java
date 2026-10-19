package ch.rupfizupfi.deck.device.api;

/**
 * The scheduling class a gated {@link Drive} gives each call. The operation implies its lane;
 * a caller never names one, so it cannot mislabel a call. Declaration order is dispatch order: a
 * gated provider dispatches the earliest lane with work, first-in first-out within a lane. Since
 * contract 1.5.0.
 *
 * <table>
 * <caption>Which call runs in which lane</caption>
 * <tr><th>Lane</th><th>Calls</th></tr>
 * <tr><td>{@link #STOP}</td><td>{@link Drive#stop()}; {@link Drive#setControlParameters} with
 *     neither start nor general enable true, at least one of them false, and direction, LOC/REM
 *     and ramp select null (the one-write stop);
 *     {@code setStart(false)}; {@code setGeneralEnable(false)};
 *     {@code setSpeedReferenceValueAsRpm(0)}</td></tr>
 * <tr><td>{@link #CONTROL}</td><td>every other control-word write: any other
 *     {@code setControlParameters}, a de-energizing one that also sets direction, LOC/REM or the
 *     ramp bit included;
 *     {@code setStart(true)}; {@code setGeneralEnable(true)}; {@code setDirection};
 *     {@code setUseSecondRamp}</td></tr>
 * <tr><td>{@link #SETPOINT}</td><td>a non-zero {@code setSpeedReferenceValueAsRpm};
 *     {@code setSecondSpeedRampTime}</td></tr>
 * <tr><td>{@link #STATUS}</td><td>every read</td></tr>
 * <tr><td>{@link #CONFIG}</td><td>{@code setActionInCaseOfCommunicationError};
 *     {@code enableLinkLossStop} (CFW11 P0313/P0314)</td></tr>
 * </table>
 * <p>
 * Every STOP-lane call bumps the {@linkplain Drive#stopEpoch() stop epoch}, so a zero-speed write
 * fences open linked groups and cancels queued CONTROL and SETPOINT work like {@code stop()} does.
 */
public enum DriveLane {

    /** Stop, de-energize, zero speed. Its deadline is {@link #STOP_DEADLINE_MILLIS}. */
    STOP,

    /** Start, enable, direction and ramp select. */
    CONTROL,

    /** Speed reference and ramp times. */
    SETPOINT,

    /** Reads. */
    STATUS,

    /** The link-loss stop's parameters. */
    CONFIG;

    /**
     * The STOP lane's deadline from submission, for every gated provider: the deck's tier 1
     * waited this long for its drive lock before 1.5.0, so its "wedged" outcome keeps its meaning.
     */
    public static final long STOP_DEADLINE_MILLIS = 1500;
}
