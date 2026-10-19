package ch.rupfizupfi.deck.device.frequencyinverter;

/**
 * One drive poll as broadcast on {@code /topic/frequency-inverter-info}, in engineering units:
 * {@link FrequencyInverterDevice#toInfo} scales the drive's tenths (see {@code Drive#getMotorData}).
 */
public class Info {
    public int id;
    /** Measured motor speed, rpm. */
    public double speed;
    public boolean start;
    public boolean generalEnable;
    public boolean useSecondRamp;
    public boolean directionIsForward;
    /** Amperes. */
    public double motorCurrent;
    /** Volts. */
    public int motorVoltage;
    /** Percent of the motor's rated torque, signed — not Nm. */
    public double motorTorquePercent;
}
