package ch.rupfizupfi.deck.testrunner;

import ch.rupfizupfi.deck.testrunner.measure.SensorReconnector;
import ch.rupfizupfi.deck.testrunner.measure.MeasurementLoop;
import ch.rupfizupfi.deck.device.DeviceService;
import ch.rupfizupfi.deck.device.loadcell.LoadCellDevice;
import ch.rupfizupfi.deck.filesystem.CSVStoreService;
import ch.rupfizupfi.deck.rig.RigLimits;
import ch.rupfizupfi.deck.testrunner.measure.ForceLimits;
import ch.rupfizupfi.deck.testrunner.measure.RunEventSink;
import ch.rupfizupfi.deck.testrunner.run.RunControl;
import ch.rupfizupfi.deck.testrunner.run.RunLoop;
import jakarta.annotation.Nullable;

/**
 * One run's devices as its {@link RunLoop} uses them: the {@link MeasurementLoop} and its
 * {@link SensorReconnector}, created by the start sequence, and the drive connection this run
 * made. Moved out of {@code AbstractTest} (OQ-64 step 7), where every test type carried them.
 * <p>
 * Written on the loop thread; the runner's snapshot reads the measurement loop from request
 * threads, hence volatile.
 */
final class RunDevices implements RunLoop.Devices {
    /** Motor energization must never be gated on anything weaker than a real, fresh sample. */
    static final long LOAD_CELL_STARTUP_TIMEOUT_MS = 2000;

    private final long testResultId;
    private final DeviceService deviceService;
    private final TestRunnerFactory factory;
    private final CSVStoreService.TestRunFiles runFiles;
    private final RecoveryGates gates;
    /** The rig limits this run was validated against; bounds the load cell's plausibility check. */
    private final RigLimits rigLimits;
    private final GapRecorder gapRecorder;

    private volatile MeasurementLoop measurementLoop;
    /** Null until the measurement loop exists; a run without one holds and aborts but never resumes. */
    private volatile SensorReconnector reconnector;
    private boolean driveConnected = false;

    RunDevices(long testResultId, DeviceService deviceService, TestRunnerFactory factory,
               CSVStoreService.TestRunFiles runFiles, RecoveryGates gates, RigLimits rigLimits,
               GapRecorder gapRecorder) {
        this.testResultId = testResultId;
        this.deviceService = deviceService;
        this.factory = factory;
        this.runFiles = runFiles;
        this.gates = gates;
        this.rigLimits = rigLimits;
        this.gapRecorder = gapRecorder;
    }

    @Override
    public void startMeasurement(ForceLimits limits, RunEventSink sink) {
        LoadCellDevice loadCell = deviceService.getLoadCell();
        MeasurementLoop cell = factory.createMeasurementLoop(limits, loadCell, runFiles, sink, gates,
                rigLimits, gapRecorder);
        this.measurementLoop = cell;
        cell.start();
        this.reconnector = factory.createReconnector(loadCell, cell, gates);
    }

    /**
     * Refuses unless the load cell delivers a fresh sample; connect() returning proves nothing, as
     * the driver opens the port on its own thread. Then waits for the watchdog's own loop: without
     * that, a stream dying in between leaves the motor pulling with no detector armed.
     */
    @Override
    public void awaitFirstSample() {
        if (!deviceService.getLoadCell().awaitFreshMeasurement(LOAD_CELL_STARTUP_TIMEOUT_MS)) {
            throw new IllegalStateException(
                    "no load cell measurement within " + LOAD_CELL_STARTUP_TIMEOUT_MS
                            + " ms - refusing to energize the motor without force feedback");
        }

        MeasurementLoop watchdog = this.measurementLoop;
        boolean watchdogArmed;
        try {
            watchdogArmed = watchdog == null || watchdog.awaitFirstSample(LOAD_CELL_STARTUP_TIMEOUT_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while waiting for the force watchdog to arm", e);
        }
        if (!watchdogArmed) {
            throw new IllegalStateException("the force watchdog saw no measurement within "
                    + LOAD_CELL_STARTUP_TIMEOUT_MS + " ms - refusing to energize the motor unwatched");
        }
    }

    @Override
    public void connectDrive() {
        deviceService.getFrequencyInverter().connect();
        driveConnected = true;
    }

    @Override
    public RunControl.Envelope envelope() {
        MeasurementLoop cell = this.measurementLoop;
        return new RunControl.Envelope(cell.getMinValue(), cell.getMaxValue());
    }

    @Override
    public void resetEnvelopeMin(float value) {
        measurementLoop.setMinValue(value);
    }

    @Override
    public void resetEnvelopeMax(float value) {
        measurementLoop.setMaxValue(value);
    }

    @Override
    public float lastForce() {
        MeasurementLoop cell = this.measurementLoop;
        return cell == null ? Float.NaN : cell.getLastForce();
    }

    @Override
    public RunEventSink timedTurns() {
        return measurementLoop.timedTurns();
    }

    /**
     * Hands the loss to the reconnector on its own worker; the loop keeps serving the hold (its
     * deadline, an Abort, a Stop) while a reconnect takes seconds.
     */
    @Override
    public boolean startRecovery(String reason, float lastKnownForce, double envelopeNewton, RunEventSink sink) {
        SensorReconnector current = reconnector;
        if (current == null) {
            return false;
        }
        current.startRecovery(reason, lastKnownForce, envelopeNewton, sink, "sensor-recovery-" + testResultId);
        return true;
    }

    @Override
    public void resetEdges() {
        MeasurementLoop cell = measurementLoop;
        if (cell != null) {
            cell.resetEdges();
        }
    }

    @Override
    public boolean freshDataFlowing(long freshTimeoutMs, long maxAgeMs) {
        LoadCellDevice loadCell = deviceService.getLoadCell();
        return loadCell.awaitFreshMeasurement(freshTimeoutMs) && loadCell.isDataFlowing(maxAgeMs);
    }

    @Override
    public void endHold() {
        measurementLoop.endHold();
    }

    @Override
    public void teardown() {
        MeasurementLoop cell = measurementLoop;
        if (cell != null) {
            // stop(), not setRunning(false): a thread parked in a hold would outlive destroy().
            cell.stop();
        }
        SensorReconnector current = reconnector;
        if (current != null) {
            current.shutdownNow();
        }
    }

    @Override
    public void destroy() {
        // Only balance a connect actually made, or the shared reference count goes negative.
        if (driveConnected) {
            driveConnected = false;
            deviceService.getFrequencyInverter().disconnect();
        }
    }

    /** The measurement loop, null before the start sequence created it. */
    @Nullable MeasurementLoop measurementLoop() {
        return measurementLoop;
    }

    long droppedSampleCount() {
        return deviceService.getLoadCell().getDroppedSampleCount();
    }
}
