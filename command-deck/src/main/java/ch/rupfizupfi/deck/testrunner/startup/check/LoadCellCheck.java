package ch.rupfizupfi.deck.testrunner.startup.check;

import ch.rupfizupfi.deck.device.DeviceService;
import ch.rupfizupfi.deck.device.loadcell.LoadCellDevice;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Verifies that the load cell actually delivers measurements before a test is allowed to start.
 * <p>
 * Neither {@code connect()} nor {@code isConnected()} proves anything: the driver opens the port on
 * its own thread, so an unplugged device still reports itself connected. Only a fresh measurement does.
 */
public class LoadCellCheck extends AbstractCheck {
    private static final Logger logger = LoggerFactory.getLogger(LoadCellCheck.class);

    private static final long FRESH_MEASUREMENT_TIMEOUT_MS = 2000;

    /**
     * Age below which an already-open device counts as live without waiting; tolerates a descheduled
     * 20 ms reader.
     */
    private static final long ALREADY_FLOWING_MAX_AGE_MS = 500;

    public DeviceService deviceService;

    public LoadCellCheck(DeviceService deviceService) {
        this.deviceService = deviceService;
    }

    @Override
    public void execute() throws CheckFailedException {
        LoadCellDevice loadCell = deviceService.getLoadCell();

        // Only a connect() that returned normally may be balanced, or another holder's reference is lost.
        boolean connected = false;
        try {
            try {
                loadCell.connect();
                connected = true;
            } catch (Throwable t) {
                // Includes UnsatisfiedLinkError from the native binding.
                logger.error("Load cell check could not open the device", t);
                throw new CheckFailedException("Could not open the load cell: " + t.getClass().getSimpleName()
                        + (t.getMessage() != null ? " - " + t.getMessage() : "")
                        + ". Check that the load cell is plugged in and no other program is using it.");
            }

            // The dashboard may already hold the device open and streaming.
            if (loadCell.isDataFlowing(ALREADY_FLOWING_MAX_AGE_MS)) {
                logger.info("Load cell check passed, device was already streaming (data newer than {} ms)",
                        ALREADY_FLOWING_MAX_AGE_MS);
                return;
            }

            if (!loadCell.awaitFreshMeasurement(FRESH_MEASUREMENT_TIMEOUT_MS)) {
                logger.error("Load cell check failed, no measurement within {} ms", FRESH_MEASUREMENT_TIMEOUT_MS);
                throw new CheckFailedException("No measurement from the load cell within "
                        + FRESH_MEASUREMENT_TIMEOUT_MS + "ms - check the USB connection of the load cell "
                        + "and that the load cell amplifier is powered on.");
            }

            logger.info("Load cell check passed, fresh measurement received within {} ms",
                    FRESH_MEASUREMENT_TIMEOUT_MS);
        } finally {
            if (connected) {
                // Released: MeasurementLoop connects again for the run.
                try {
                    loadCell.disconnect();
                } catch (Exception e) {
                    // Swallowed so it cannot replace a CheckFailedException on its way out.
                    logger.warn("Failed to release the load cell after the startup check", e);
                }
            }
        }
    }
}
