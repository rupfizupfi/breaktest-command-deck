package ch.rupfizupfi.deck.testrunner;

import ch.rupfizupfi.deck.testrunner.measure.SensorReconnector;
import ch.rupfizupfi.deck.testrunner.measure.MeasurementLoop;
import ch.rupfizupfi.deck.device.DeviceService;
import ch.rupfizupfi.deck.device.frequencyinverter.FrequencyInverterDevice;
import ch.rupfizupfi.deck.device.loadcell.LoadCellDevice;
import ch.rupfizupfi.deck.filesystem.CSVStoreService;
import ch.rupfizupfi.deck.rig.RigLimits;
import ch.rupfizupfi.deck.testrunner.measure.ForceLimits;
import ch.rupfizupfi.deck.testrunner.measure.RunEventSink;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * What a run's devices answer the run loop with (OQ-64 step 7, moved out of
 * {@code AbstractTestRecoveryTest}): the loss path itself, its order and the envelope floor live in
 * {@code RunLoop} and are proven in {@code RunLoopTest}.
 */
class RunDevicesTest {

    private TestRunnerFactory factory;
    private LoadCellDevice loadCell;
    private FrequencyInverterDevice drive;
    private MeasurementLoop cell;
    private SensorReconnector reconnector;
    private RunDevices devices;

    @BeforeEach
    void wireRun() {
        factory = mock(TestRunnerFactory.class);
        loadCell = mock(LoadCellDevice.class);
        drive = mock(FrequencyInverterDevice.class);
        cell = mock(MeasurementLoop.class);
        reconnector = mock(SensorReconnector.class);
        DeviceService deviceService = mock(DeviceService.class);
        when(deviceService.getLoadCell()).thenReturn(loadCell);
        when(deviceService.getFrequencyInverter()).thenReturn(drive);
        when(factory.createMeasurementLoop(any(), any(), any(), any(), any(), any(), any())).thenReturn(cell);
        when(factory.createReconnector(loadCell, cell, null)).thenReturn(reconnector);

        devices = new RunDevices(7L, deviceService, factory,
                new CSVStoreService.TestRunFiles("force.csv", "gaps.json"), null, RigLimits.DEFAULTS, null);
    }

    @Test
    void theMeasurementLoopStartsOnTheRunsLimitsAndGetsItsReconnector() {
        ForceLimits limits = new ForceLimits(100, -100, Double.POSITIVE_INFINITY);
        RunEventSink sink = event -> { };

        devices.startMeasurement(limits, sink);

        verify(factory).createMeasurementLoop(eq(limits), eq(loadCell), any(), eq(sink), any(), eq(RigLimits.DEFAULTS), any());
        verify(cell).start();
        assertThat(devices.measurementLoop()).isSameAs(cell);
    }

    @Test
    void recoveryRunsOnTheReconnectorsWorkerNamedAfterTheRun() {
        devices.startMeasurement(new ForceLimits(100, -100, Double.POSITIVE_INFINITY), event -> { });
        RunEventSink sink = event -> { };

        assertThat(devices.startRecovery("no measurement for 250 ms", 12f, 750.0, sink)).isTrue();

        verify(reconnector).startRecovery("no measurement for 250 ms", 12f, 750.0, sink, "sensor-recovery-7");
    }

    @Test
    void withoutAReconnectorTheHoldCanOnlyEndInAnAbort() {
        assertThat(devices.startRecovery("no measurement for 250 ms", 12f, 750.0, event -> { })).isFalse();
    }

    /** I10: the start's proof needs a fresh device sample, then the watchdog's own first sample. */
    @Test
    void noFreshDeviceSampleRefusesTheEnergize() {
        when(loadCell.awaitFreshMeasurement(anyLong())).thenReturn(false);

        assertThatThrownBy(() -> devices.awaitFirstSample())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("refusing to energize the motor without force feedback");
    }

    @Test
    void aWatchdogThatSawNoSampleRefusesTheEnergize() throws InterruptedException {
        devices.startMeasurement(new ForceLimits(100, -100, Double.POSITIVE_INFINITY), event -> { });
        when(loadCell.awaitFreshMeasurement(anyLong())).thenReturn(true);
        when(cell.awaitFirstSample(anyLong())).thenReturn(false);

        assertThatThrownBy(() -> devices.awaitFirstSample())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("refusing to energize the motor unwatched");
    }

    /** I10: the resume's fresh-data proof needs a fresh sample and a flowing stream. */
    @Test
    void freshDataNeedsBothAFreshSampleAndAFlowingStream() {
        when(loadCell.awaitFreshMeasurement(2000)).thenReturn(true);
        when(loadCell.isDataFlowing(250)).thenReturn(false);
        assertThat(devices.freshDataFlowing(2000, 250)).isFalse();

        when(loadCell.isDataFlowing(250)).thenReturn(true);
        assertThat(devices.freshDataFlowing(2000, 250)).isTrue();
    }

    @Test
    void noFreshSampleNeverAsksWhetherTheStreamFlows() {
        when(loadCell.awaitFreshMeasurement(anyLong())).thenReturn(false);

        assertThat(devices.freshDataFlowing(2000, 250)).isFalse();
        verify(loadCell, never()).isDataFlowing(eq(250L));
    }

    @Test
    void onlyAConnectMadeIsBalancedAndOnlyOnce() {
        devices.destroy();
        verify(drive, never()).disconnect();

        devices.connectDrive();
        devices.destroy();
        devices.destroy();
        verify(drive).connect();
        verify(drive).disconnect();
    }

    @Test
    void theTeardownStopsTheLoopAndTheReconnectorAndToleratesNeither() {
        devices.teardown();

        devices.startMeasurement(new ForceLimits(100, -100, Double.POSITIVE_INFINITY), event -> { });
        devices.teardown();
        verify(cell).stop();
        verify(reconnector).shutdownNow();
    }

    @Test
    void noMeasurementLoopYetMeansNoKnownForce() {
        assertThat(devices.lastForce()).isNaN();
    }
}
