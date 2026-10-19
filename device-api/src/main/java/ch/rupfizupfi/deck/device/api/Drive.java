package ch.rupfizupfi.deck.device.api;

import java.time.Duration;
import java.util.Map;
import java.util.function.Consumer;

/**
 * The frequency inverter operations the deck uses. An interface because the vendor's {@code Cfw11}
 * is a final Kotlin class.
 * <p>
 * Every call may fail. A request that reaches no drive throws {@link DriveCommunicationException}
 * (since contract 1.4.0; earlier plugins throw their own types), and any call on a handle after
 * {@link #close()} throws. {@code DriveContractTest} in this build's test fixtures holds every
 * provider to the behaviour documented here.
 * <p>
 * Who serializes calls is the provider's {@link #gating()} answer (since contract 1.5.0): a
 * {@link DriveGating#CALLER_SERIALIZES} handle must never see two calls at once, a
 * {@link DriveGating#DRIVER_SERIALIZES} one takes calls from any thread and dispatches them by
 * {@link DriveLane}, a stop first, and feeds the link watchdog it armed
 * ({@link #enableLinkLossStop}).
 * <p>
 * No vendor type may appear in this package, so the deck compiles without
 * {@code drivers/usbmodbus.jar}. See
 * {@code doc/dev/backend/device-api.md}.
 */
public interface Drive {

    /**
     * The {@link #setActionInCaseOfCommunicationError} code that removes general enable on a lost
     * link, so the motor coasts. The one action the deck sets; since contract 1.2.0.
     */
    int COMMUNICATION_ERROR_DISABLE_VIA_GENERAL_ENABLE = 2;

    /**
     * Writes several control bits in one register write. A null leaves that bit untouched, which is
     * the only way to set the others without also forcing local/remote.
     */
    void setControlParameters(Boolean start, Boolean generalEnable, Boolean directionIsForward,
                              Boolean localRemote, Boolean useSecondRamp);

    /**
     * The control bits as last written, read back from the drive's command register — what was
     * requested, not what the drive is doing.
     * <p>
     * Must contain the keys {@code start}, {@code generalEnable}, {@code useSecondRamp},
     * {@code directionIsForward}; {@code FrequencyInverterDevice} treats a missing or null one as a
     * poll failure naming the key. A provider may add more: the CFW11 driver's {@code localRemote}
     * is the LOC/REM <em>request</em> bit, true when remote was requested. The drive acts on it only
     * when its LOC/REM selection source (CFW11 P0220) is the serial interface, so it never says which
     * source is active — {@link #readControlSource} does.
     */
    Map<String, Boolean> getControlParameters();

    /**
     * The command source the drive is actually obeying at this moment, read from the drive's own
     * status — never derived from the {@code localRemote} request in
     * {@link #getControlParameters}. Read-only: no write reaches the drive.
     * <p>
     * {@link ControlSource#UNKNOWN} when the provider cannot report it. A failed read throws, as
     * every other read does. A caller acting on the answer treats both as "not known to be
     * {@link ControlSource#LOCAL}".
     * <p>
     * Added in contract 1.3.0 as a default method. The fallback answers
     * {@link ControlSource#UNKNOWN}: an older plugin still loads, and the deck keeps its
     * pre-1.3.0 behaviour of treating the drive as remote-controlled.
     */
    default ControlSource readControlSource() {
        return ControlSource.UNKNOWN;
    }

    void setStart(boolean start);

    /** False de-energizes the output stage and lets the motor coast — never ramps. */
    void setGeneralEnable(boolean generalEnable);

    /** True is release, false is pull; the sign convention of measured speed is unverified. */
    void setDirection(boolean directionIsForward);

    boolean getDirection();

    void setUseSecondRamp(boolean useSecondRamp);

    /**
     * Both times in tenths of a second — the call sites multiply seconds by 10.
     * <p>
     * A provider refuses a time its drive cannot hold before anything is written, rather than
     * clamping or wrapping it: the CFW11 takes 0..9990 (0.0..999.0 s, P0102/P0103), and its driver
     * refuses anything else since the H-11 fix (2026-10-13); the simulator mirrors that bound. The
     * deck's {@code TestParameterRules} holds every parameter-derived ramp to the same range, so a
     * run is refused at its gate instead of here.
     *
     * @throws IllegalArgumentException if either time is negative or above what the drive holds
     */
    void setSecondSpeedRampTime(int accelerationRampTime, int decelerationRampTime);

    /**
     * The speed setpoint in motor-shaft rpm, as {@code RunParameters#toMotorRpm} produces it. Zero is
     * part of every stop and must go out whatever else the provider cannot do. A non-zero rpm the
     * drive cannot represent is refused, never clamped or wrapped: the CFW11 driver scales against
     * the synchronous speed it derives at open from the drive's motor nameplate (P0402/P0403), so
     * its ceiling is just under 4 x that speed, and it refuses every non-zero rpm while that
     * nameplate is inconsistent.
     */
    void setSpeedReferenceValueAsRpm(int rpm);

    /**
     * MEASURED motor speed. The commanded setpoint reads back 0 the instant 0 is written, so it can
     * never verify a stop — see {@code MotorSafetyController#verifyStopped}.
     */
    int getMotorSpeedValueAsRpm();

    /**
     * Must contain the keys {@code speed}, {@code current}, {@code voltage}, {@code torque};
     * {@code FrequencyInverterDevice} treats a missing or null one as a poll failure naming the key.
     * <p>
     * Values are the drive's integers in their own resolution, unscaled — the deck converts to
     * engineering units, in {@code FrequencyInverterDevice#toInfo}:
     * <ul>
     * <li>{@code speed}: measured motor speed, rpm.</li>
     * <li>{@code current}: motor current, tenths of an ampere, never negative (CFW11 P0003,
     *     0..45000 for 0.0..4500.0 A).</li>
     * <li>{@code voltage}: motor voltage, volts (CFW11 P0007).</li>
     * <li>{@code torque}: motor torque, tenths of a percent of the motor's rated torque —
     *     not Nm — and signed (CFW11 P0009, -10000..10000 for -1000.0..1000.0 %).</li>
     * </ul>
     * A CFW11 driver jar built before the H-11 fix (2026-10-13) returns torque unsigned, so a
     * negative torque reads as 65536 minus its magnitude; the units are unchanged.
     */
    Map<String, Integer> getMotorData();

    /**
     * What the drive does once {@link #enableLinkLossStop} detects a silent link;
     * {@link #COMMUNICATION_ERROR_DISABLE_VIA_GENERAL_ENABLE} is the deck's choice. Inert until a
     * link-loss timeout is set.
     * <p>
     * Idempotent, like {@link #enableLinkLossStop}: a drive may refuse this setting while the shaft
     * turns, and every energize, a resume over a coasting motor included, re-sends the same value.
     * A provider writes only a changed value and verifies what the drive kept.
     */
    void setActionInCaseOfCommunicationError(int action);

    /**
     * Arms the drive's own link watchdog: from now on, if no request reaches the drive for
     * {@code timeout}, the drive applies the communication-error action by itself. The setting
     * outlives this handle; nothing disarms it.
     * <p>
     * Who keeps a request reaching the drive depends on {@link #gating()}. A
     * {@link DriveGating#DRIVER_SERIALIZES} provider does it by itself (since contract 1.7.0): from
     * a successful arm until a de-energizing call ({@link #stop()}, the one-write stop,
     * {@code setStart(false)}, {@code setGeneralEnable(false)}) or {@link #close()}, it injects a
     * read whenever its own traffic pauses, so a silent caller does not starve the watchdog while
     * the motor runs. The feed pauses at that de-energizing call and resumes only at the next
     * successful arm, so the caller owes a fresh {@code enableLinkLossStop} before each energize. A
     * {@link DriveGating#CALLER_SERIALIZES} provider does nothing of the kind: there the caller owes
     * the drive a request at least once per {@code timeout} for as long as the motor runs.
     * <p>
     * A provider rounds {@code timeout} up to its drive's resolution, refuses to arm a watchdog
     * whose configured action would not stop the motor, and writes only a changed value.
     * <p>
     * Added in contract 1.2.0 as a default method, so a plugin built against 1.1.0 still loads.
     * Its fallback throws, never silently does nothing: the deck refuses to energize a drive that
     * cannot stop itself.
     *
     * @throws IllegalArgumentException if {@code timeout} is not positive or exceeds what the
     *                                  drive can represent
     * @throws IllegalStateException    if the configured communication-error action would not stop
     *                                  the motor, or the drive did not keep the timeout
     * @throws UnsupportedOperationException if the plugin predates contract 1.2.0
     */
    default void enableLinkLossStop(Duration timeout) {
        throw new UnsupportedOperationException(getClass().getName()
                + " was built against device-api older than 1.2.0 and cannot arm the drive's link-loss"
                + " stop. Rebuild the driver plugin against this contract and replace its jar.");
    }

    /**
     * Stops the motor: start and general enable cleared, the speed reference zero; direction and
     * ramp select untouched. Idempotent, so a caller may repeat it, and in the STOP lane. On a
     * {@link DriveGating#DRIVER_SERIALIZES} handle it is never blocked by another in-flight call for
     * longer than one bus transaction, bumps the {@linkplain #stopEpoch() stop epoch} and fails
     * every queued CONTROL and SETPOINT call with {@link StaleStopEpochException}.
     * <p>
     * Added in contract 1.5.0 as a default method. The fallback is the deck's pre-1.5.0 stop, two
     * calls: the one-write {@code setControlParameters(false, false, null, null, null)}, then
     * {@code setSpeedReferenceValueAsRpm(0)}, which goes out whatever the first threw - an
     * {@link Error}, or a checked vendor exception an older plugin lets through undeclared, included.
     * The first failure is rethrown with its own type, the second suppressed in it unless it is the
     * same instance (a driver rethrowing a stored terminal fault).
     */
    default void stop() {
        Throwable failure = null;
        try {
            setControlParameters(false, false, null, null, null);
        } catch (Throwable t) {
            failure = t;
        }
        try {
            setSpeedReferenceValueAsRpm(0);
        } catch (Throwable t) {
            if (failure == null) {
                failure = t;
            } else if (t != failure) {
                failure.addSuppressed(t);
            }
        }
        if (failure != null) {
            throw Drive.<RuntimeException>sneakyThrow(failure);
        }
    }

    /** Rethrows {@code failure} unwrapped, checked or not: {@link #stop()} must not change its type. */
    @SuppressWarnings("unchecked")
    private static <E extends Throwable> E sneakyThrow(Throwable failure) throws E {
        throw (E) failure;
    }

    /**
     * Who serializes calls on this handle. Added in contract 1.5.0; the fallback answers
     * {@link DriveGating#CALLER_SERIALIZES}, which is what every older plugin is.
     */
    default DriveGating gating() {
        return DriveGating.CALLER_SERIALIZES;
    }

    /**
     * A counter every STOP-lane call on this handle bumps by one when it is submitted; it never
     * decreases. Read it, check whatever decides that the motor may run, then submit a
     * {@link #runLinked linked group} fenced on the value read: a stop in between rejects the group.
     * <p>
     * Added in contract 1.5.0. The fallback throws: an ungated handle has no epoch to fence on.
     *
     * @throws UnsupportedOperationException on a {@link DriveGating#CALLER_SERIALIZES} handle
     */
    default long stopEpoch() {
        throw ungated("keep a stop epoch");
    }

    /**
     * Runs {@code body} as one linked group: its calls, made on the {@link Drive} passed to it, go
     * out back to back, and only the STOP lane may dispatch between them. The group is admitted in
     * the CONTROL lane and only while the stop epoch still equals {@code expectedEpoch}; a stop
     * submitted before admission rejects it with nothing run, and any stop that goes out while it is
     * open - even one submitted just before {@code expectedEpoch} was read - makes its next call
     * throw. Either way the caller sees {@link StaleStopEpochException}.
     * <p>
     * {@code body} runs on the calling thread and must use only the {@code Drive} it is given.
     * For the whole body - also after a stop cut the group or the group was lost - the body's
     * thread is refused, with {@link IllegalStateException} and without reaching the drive: a
     * non-STOP call on this handle or on its async view; {@code runLinked} (a nested group) and
     * {@code close()} on this handle or on the {@code Drive} passed to the body; and
     * {@code async()} on the {@code Drive} passed to the body. A stop is never refused: a STOP-lane
     * call ({@link DriveLane}'s table, so also {@code setSpeedReferenceValueAsRpm(0)},
     * {@code setStart(false)}, {@code setGeneralEnable(false)} and the one-write stop) goes out and
     * cuts the group short, whether made on this handle, on its async view or on the {@code Drive}
     * passed to the body; the body's next call on that {@code Drive} then throws
     * {@link StaleStopEpochException}. A body that submits nothing for longer than the CONTROL
     * deadline loses the group, so it does no slow work between calls.
     * <p>
     * Added in contract 1.5.0. The fallback throws, never runs {@code body} unfenced.
     *
     * @throws StaleStopEpochException if a stop intervened before or during the group
     * @throws UnsupportedOperationException on a {@link DriveGating#CALLER_SERIALIZES} handle
     */
    default void runLinked(long expectedEpoch, Consumer<Drive> body) {
        throw ungated("run a linked group");
    }

    /**
     * The asynchronous view of this handle; see {@link DriveAsync}. Added in contract 1.5.0. The
     * fallback throws: an ungated handle cannot accept a second call while one is in flight.
     *
     * @throws UnsupportedOperationException on a {@link DriveGating#CALLER_SERIALIZES} handle
     */
    default DriveAsync async() {
        throw ungated("offer the async view");
    }

    private UnsupportedOperationException ungated(String what) {
        return new UnsupportedOperationException(getClass().getName()
                + " was built against device-api older than 1.5.0 and cannot " + what
                + ": it does not gate its own calls. Rebuild the driver plugin against this contract"
                + " and replace its jar.");
    }

    /**
     * Releases the underlying USB handle, which the vendor constructor grabs eagerly.
     * <p>
     * On a {@link DriveGating#DRIVER_SERIALIZES} handle (since contract 1.5.0) it at once rejects
     * new calls, a stop included, and fails every queued call outside the STOP lane with
     * {@link IllegalStateException}. The provider's I/O thread then finishes the transaction in
     * flight and sends every STOP-lane call queued before {@code close()}. {@code close()} waits for
     * that at most {@link DriveLane#STOP_DEADLINE_MILLIS} (since contract 1.6.0; before, without a
     * bound): once it returns normally nothing is in flight and the port is closed, so a caller may
     * open a fresh handle on the same drive without two sessions overlapping. Past the bound it
     * throws {@link DriveClosePendingException} - nothing aborts a transfer - and the port is still
     * held; a caller must not open a fresh handle then. What the contract guarantees is that the
     * port is closed no later than the first later {@code close()} that returns normally, so calling
     * it again is how a caller waits for the port. A provider that hands its port close to the I/O
     * thread (the {@code lastAct} of {@code TransactionScheduler}, as {@code GatedDrive} does) also
     * closes the port by itself once the transfer ends; one that closes the port on the closing
     * thread after the scheduler's close leaves it open until that later {@code close()}.
     * Idempotent: a later call waits the same bound. Refused with {@link IllegalStateException}
     * from inside a {@linkplain #runLinked linked group}, and on the provider's own I/O thread,
     * which would wait for itself - only a provider's operation calling back into its handle runs
     * there; async completions never do.
     *
     * @throws DriveClosePendingException on a gated handle whose transaction in flight outlasts
     *                                    the STOP deadline
     */
    void close();
}
