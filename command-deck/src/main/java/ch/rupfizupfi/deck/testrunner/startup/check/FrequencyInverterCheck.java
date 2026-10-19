package ch.rupfizupfi.deck.testrunner.startup.check;

import ch.rupfizupfi.deck.device.api.Drive;
import ch.rupfizupfi.deck.device.frequencyinverter.FrequencyInverterDevice;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Refuses a test unless one read through the shared drive handle proves the frequency inverter is
 * answering now. Nothing is written, so the check can neither energize nor move the motor, and its
 * {@code connect()} is balanced so the run's own connect still opens a fresh reference count.
 */
public class FrequencyInverterCheck extends AbstractCheck {
    private static final Logger logger = LoggerFactory.getLogger(FrequencyInverterCheck.class);

    private final FrequencyInverterDevice frequencyInverter;

    public FrequencyInverterCheck(FrequencyInverterDevice frequencyInverter) {
        this.frequencyInverter = frequencyInverter;
    }

    @Override
    public void execute() throws CheckFailedException {
        // Only a connect() that returned normally may be balanced; see LoadCellCheck.execute().
        boolean connected = false;
        try {
            try {
                frequencyInverter.connect();
                connected = true;
            } catch (Throwable t) {
                // Includes UnsatisfiedLinkError from the native binding.
                logger.error("Frequency inverter check could not open the device", t);
                throw new CheckFailedException("Could not open the frequency inverter: "
                        + t.getClass().getSimpleName()
                        + (t.getMessage() != null ? " - " + t.getMessage() : "")
                        + ". Check the USB connection of the inverter and that no other program "
                        + "is using it.");
            }

            try {
                // Read-only. Exception, because the driver's checked exceptions cross Drive undeclared.
                frequencyInverter.queryDrive(Drive::getControlParameters);
            } catch (Exception e) {
                logger.error("Frequency inverter check got no answer from the drive", e);
                throw new CheckFailedException("Frequency inverter is not answering: "
                        + e.getClass().getSimpleName()
                        + (e.getMessage() != null ? " - " + e.getMessage() : "")
                        + ". Check the USB connection of the inverter and that it is powered on.");
            }

            logger.info("Frequency inverter check passed, the drive answered a control-parameter read");
        } finally {
            if (connected) {
                try {
                    frequencyInverter.disconnect();
                } catch (Exception e) {
                    // Swallowed so it cannot replace a CheckFailedException on its way out.
                    logger.warn("Failed to release the frequency inverter after the startup check", e);
                }
            }
        }
    }
}
