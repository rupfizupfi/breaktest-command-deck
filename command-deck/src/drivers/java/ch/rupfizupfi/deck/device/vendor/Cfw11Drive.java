package ch.rupfizupfi.deck.device.vendor;

import ch.rupfizupfi.deck.device.api.Drive;
import ch.rupfizupfi.usbmodbus.Cfw11;

import java.util.Map;

/**
 * {@link Drive} over the vendor {@code Cfw11}. Pure delegation by rule: this package is the only
 * code the simulated path never exercises, so anything clever here first runs on the bench.
 * <p>
 * Lives in the optional {@code drivers} source set, which is compiled only when both vendor jars
 * are in {@code lib/}. Nothing in {@code src/main} may reference it.
 */
public class Cfw11Drive implements Drive {

    private final Cfw11 cfw11;

    /** The handle is already open â€” constructing {@code Cfw11} eagerly grabs the USB device. */
    public Cfw11Drive(Cfw11 cfw11) {
        this.cfw11 = cfw11;
    }

    @Override
    public void setControlParameters(Boolean start, Boolean generalEnable, Boolean directionIsForward,
                                     Boolean localRemote, Boolean useSecondRamp) {
        cfw11.setControlParameters(start, generalEnable, directionIsForward, localRemote, useSecondRamp);
    }

    @Override
    public Map<String, Boolean> getControlParameters() {
        return cfw11.getControlParameters();
    }

    @Override
    public void setStart(boolean start) {
        cfw11.setStart(start);
    }

    @Override
    public void setGeneralEnable(boolean generalEnable) {
        cfw11.setGeneralEnable(generalEnable);
    }

    @Override
    public void setDirection(boolean directionIsForward) {
        cfw11.setDirection(directionIsForward);
    }

    @Override
    public boolean getDirection() {
        return cfw11.getDirection();
    }

    @Override
    public void setUseSecondRamp(boolean useSecondRamp) {
        cfw11.setUseSecondRamp(useSecondRamp);
    }

    @Override
    public void setSecondSpeedRampTime(int accelerationRampTime, int decelerationRampTime) {
        cfw11.setSecondSpeedRampTime(accelerationRampTime, decelerationRampTime);
    }

    @Override
    public void setSpeedReferenceValueAsRpm(int rpm) {
        cfw11.setSpeedReferenceValueAsRpm(rpm);
    }

    @Override
    public int getMotorSpeedValueAsRpm() {
        return cfw11.getMotorSpeedValueAsRpm();
    }

    @Override
    public Map<String, Integer> getMotorData() {
        return cfw11.getMotorData();
    }

    @Override
    public void setActionInCaseOfCommunicationError(int action) {
        cfw11.setActionInCaseOfCommunicationError(action);
    }

    @Override
    public void close() {
        cfw11.getUsbComm().closeUSBComm();
    }
}

