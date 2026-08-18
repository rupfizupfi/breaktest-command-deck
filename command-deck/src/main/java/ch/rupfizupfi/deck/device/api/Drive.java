package ch.rupfizupfi.deck.device.api;

import java.util.Map;

/**
 * The frequency converter operations the deck actually uses. An interface rather than the vendor
 * type because the vendor's {@code Cfw11} is a Kotlin class and therefore final.
 * <p>
 * No vendor type may appear in this package: it is what keeps the main source set compilable
 * without {@code lib/usbmodbus.jar}. See
 * {@code doc/06-feature-work/virtual-devices/driver-api-extraction.md}.
 */
public interface Drive {

    /**
     * Writes several control bits in one register write. A null leaves that bit untouched, which is
     * the only way to set the others without also forcing local/remote.
     */
    void setControlParameters(Boolean start, Boolean generalEnable, Boolean directionIsForward,
                              Boolean localRemote, Boolean useSecondRamp);

    /** Read by {@code CFW11Device} under keys {@code start}, {@code generalEnable}, {@code useSecondRamp}, {@code directionIsForward}. */
    Map<String, Boolean> getControlParameters();

    void setStart(boolean start);

    /** False de-energizes the output stage and lets the motor coast — never ramps. */
    void setGeneralEnable(boolean generalEnable);

    /** True is release, false is pull; the sign convention of measured speed is unverified. */
    void setDirection(boolean directionIsForward);

    boolean getDirection();

    void setUseSecondRamp(boolean useSecondRamp);

    /** Both times in tenths of a second — the call sites multiply seconds by 10. */
    void setSecondSpeedRampTime(int accelerationRampTime, int decelerationRampTime);

    void setSpeedReferenceValueAsRpm(int rpm);

    /**
     * MEASURED motor speed. The commanded setpoint reads back 0 the instant 0 is written, so it can
     * never verify a stop — see {@code MotorSafetyController#verifyStopped}.
     */
    int getMotorSpeedValueAsRpm();

    /** Read by {@code CFW11Device} under keys {@code speed}, {@code current}, {@code voltage}, {@code torque}. */
    Map<String, Integer> getMotorData();

    /** 2 = disable via general enable; the drive's own backstop for a lost link. */
    void setActionInCaseOfCommunicationError(int action);

    /**
     * Releases the underlying USB handle. The one vendor member with no domain equivalent, kept here
     * because {@code new} on the vendor side eagerly grabs the device.
     */
    void close();
}
