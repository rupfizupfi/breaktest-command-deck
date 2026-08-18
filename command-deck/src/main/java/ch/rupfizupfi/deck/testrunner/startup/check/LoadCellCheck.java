package ch.rupfizupfi.deck.testrunner.startup.check;

import ch.rupfizupfi.deck.device.DeviceService;
import ch.rupfizupfi.deck.device.loadcell.LoadCellDevice;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Verifies that the load cell actually delivers measurements before a test is allowed to start.
 * <p>
 * Neither {@code connect()} nor {@code isConnected()} proves anything here: the driver opens the
 * port on its own thread, so opening a device that is not plugged in returns normally and reports
 * itself connected. The only trustworthy evidence is a measurement that arrived after we asked --
 * without it a test would energize the motor and pull the sample completely blind.
 */
public class LoadCellCheck extends AbstractCheck {
    private static final Logger logger = LoggerFactory.getLogger(LoadCellCheck.class);

    private static final long FRESH_MEASUREMENT_TIMEOUT_MS = 2000;

    /**
     * Age below which an already-open device counts as live without waiting. The reader loop ticks
     * every 20 ms, so this tolerates a descheduled reader while staying far too short to accept
     * data from a sensor that has already gone quiet.
     */
    private static final long ALREADY_FLOWING_MAX_AGE_MS = 500;

    public DeviceService deviceService;

    public LoadCellCheck(DeviceService deviceService) {
        this.deviceService = deviceService;
    }

    @Override
    public void execute() throws CheckFailedException {
        LoadCellDevice loadCell = deviceService.getLoadCell();

        // Tracked separately from any device state: only a connect() that returned normally may be
        // balanced by a disconnect(). Device.connect() raises its reference count *after* the open
        // succeeds, so disconnecting a failed connect would hit the zero clamp and log a bogus
        // warning -- and worse, decrement a reference that another holder owns.
        boolean connected = false;
        try {
            try {
                loadCell.connect();
                connected = true;
            } catch (Throwable t) {
                // Includes UnsatisfiedLinkError from the USB native binding; an operator reading the
                // test log needs the cause, not a stack trace escaping into the runner's generic handler.
                logger.error("Load cell check could not open the device", t);
                throw new CheckFailedException("Could not open the load cell: " + t.getClass().getSimpleName()
                        + (t.getMessage() != null ? " - " + t.getMessage() : "")
                        + ". Check that the load cell is plugged in and no other program is using it.");
            }

            // The dashboard or another test component may already hold the device open and streaming.
            // In that case data is provably current and there is nothing to wait for.
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
                // Deliberately released: LoadCellThread connects the device again for the actual run,
                // and the reference count has to balance across many runs or the device is never closed.
                try {
                    loadCell.disconnect();
                } catch (Exception e) {
                    // Swallowed on purpose -- a throw from here would replace the CheckFailedException
                    // that is on its way out and hide the real reason the test was refused.
                    logger.warn("Failed to release the load cell after the startup check", e);
                }
            }
        }
    }
}
