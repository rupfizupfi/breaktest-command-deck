package ch.rupfizupfi.deck.testrunner;

import ch.rupfizupfi.deck.device.loadcell.LoadCellDevice;
import ch.rupfizupfi.deck.device.loadcell.MeasurementObserver;
import ch.rupfizupfi.deck.filesystem.CSVStoreService;
import ch.rupfizupfi.dscusb.Measurement;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public class LoadCellThread implements Runnable, MeasurementObserver {
    private volatile boolean running = false;
    private final TestContext testContext;
    private volatile float minValue;
    private volatile float maxValue;
    private final CSVStoreService csvStoreService;
    private final LoadCellDevice loadCellDevice;
    private final List<Measurement> measurementBuffer = new CopyOnWriteArrayList<>();
    private final Object lock = new Object();

    LoadCellThread(TestContext testContext, LoadCellDevice loadCellDevice) {
        this.testContext = testContext;
        this.loadCellDevice = loadCellDevice;
        minValue = (float) testContext.getLowerLimit();
        maxValue = (float) testContext.getUpperLimit();
        csvStoreService = new CSVStoreService();
    }

    public void setRunning(boolean running) {
        this.running = running;
    }

    public float getMaxValue() {
        return maxValue;
    }

    public float getMinValue() {
        return minValue;
    }

    public void setMaxValue(float maxValue) {
        this.maxValue = maxValue;
    }

    public void setMinValue(float minValue) {
        this.minValue = minValue;
    }

    @Override
    public void update(List<Measurement> measurements) {
        synchronized (lock) {
            measurementBuffer.addAll(measurements);
        }
    }

    @Override
    public void run() {
        String filePath = csvStoreService.generateFilePathForTestResult(testContext.getTestResultId());

        try (BufferedWriter writer = new BufferedWriter(new FileWriter(filePath))) {
            loadCellDevice.connect();
            loadCellDevice.registerObserver(this);

            while (running) {
                if (measurementBuffer.isEmpty()) {
                    Thread.sleep(20);
                    continue;
                }

                List<Measurement> measurements;
                synchronized (lock) {
                    measurements = new ArrayList<>(measurementBuffer);
                    measurementBuffer.clear();
                }

                for (Measurement measurement : measurements) {
                    minValue = Math.min(minValue, measurement.getForce());
                    maxValue = Math.max(maxValue, measurement.getForce());

                    writer.write(measurement.getTimestamp() + "," + measurement.getForce());
                    writer.newLine();
                }

                Measurement lastMeasurement = measurements.getLast();
                if (lastMeasurement.getForce() > testContext.getUpperLimit()) {
                    testContext.sendSignal(TestContext.RELEASE_SIGNAL);
                }
                else if (lastMeasurement.getForce() < testContext.getLowerLimit()) {
                    testContext.sendSignal(TestContext.PULL_SIGNAL);
                }
            }

            loadCellDevice.disconnect();
        } catch (IOException | InterruptedException e) {
            throw new RuntimeException(e);
        }
    }
}
