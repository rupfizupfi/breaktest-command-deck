package ch.rupfizupfi.deck.device.api.support;

import ch.rupfizupfi.deck.device.api.ControlSource;
import ch.rupfizupfi.deck.device.api.Drive;

import java.time.Duration;
import java.util.Map;

/**
 * A provider's plain blocking operations behind a {@link GatedDrive}: each method is one
 * transaction with the drive and has the meaning, refusals and failure types of the {@link Drive}
 * method of the same name. {@link GatedDrive} calls them on its I/O thread only, one at a time, so
 * an implementation needs no lock of its own and must never call back into the {@code GatedDrive}.
 */
public interface DriveOperations {

    /** One transaction: start and general enable cleared, speed reference zero. */
    void stop();

    void setControlParameters(Boolean start, Boolean generalEnable, Boolean directionIsForward,
                              Boolean localRemote, Boolean useSecondRamp);

    Map<String, Boolean> getControlParameters();

    ControlSource readControlSource();

    void setStart(boolean start);

    void setGeneralEnable(boolean generalEnable);

    void setDirection(boolean directionIsForward);

    boolean getDirection();

    void setUseSecondRamp(boolean useSecondRamp);

    void setSecondSpeedRampTime(int accelerationRampTime, int decelerationRampTime);

    void setSpeedReferenceValueAsRpm(int rpm);

    int getMotorSpeedValueAsRpm();

    Map<String, Integer> getMotorData();

    void setActionInCaseOfCommunicationError(int action);

    void enableLinkLossStop(Duration timeout);

    /**
     * Closes the port. Called once, on the I/O thread as its last act - after its last transaction
     * and the last stop queued before {@code close()} - so never while one is in flight, and never
     * on the thread that opened the port. What it throws reaches the first {@link GatedDrive#close()}
     * that sees the I/O thread ended; a closer that gave up at the STOP deadline is not there to see
     * it, so the next {@code close()} reports it. Since contract 1.6.0; before, the closing thread
     * called it.
     */
    void close();
}
