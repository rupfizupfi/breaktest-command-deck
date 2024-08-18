package ch.rupfizupfi.deck.testrunner;

import ch.rupfizupfi.deck.filesystem.CSVStoreService;
import ch.rupfizupfi.dscusb.CellValueStream;
import ch.rupfizupfi.dscusb.Measurement;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;

public class LoadCellThread implements Runnable   {
    private final SimpMessagingTemplate template;
    private volatile boolean running = false;
    private BufferedWriter writer;
    private CellValueStream stream;
    private TestContext testContext;
    private volatile float minValue;
    private volatile float maxValue;
    private CSVStoreService csvStoreService;

    LoadCellThread(SimpMessagingTemplate template, TestContext testContext) {
        this.template = template;
        this.testContext = testContext;
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
    public void run() {
        String filePath = csvStoreService.generateFilePathForTestResult(testContext.getTestResultId());

        try {
            writer = new BufferedWriter(new FileWriter(filePath));
        } catch (IOException e) {
            throw new RuntimeException("Failed to create file stream", e);
        }

        var wsMeasurements = new ArrayList<Measurement>();
        stream = new CellValueStream();
        stream.startReading();

        try {
            while (running) {
                var measurements = stream.getNextValues();

                if(measurements.isEmpty()){
                    Thread.sleep(20);
                    continue;
                }

                var lastMeasurement = measurements.getLast();
                if (lastMeasurement.getForce() > testContext.getUpperLimit()) {
                    testContext.sendSignal(1);
                    minValue = lastMeasurement.getForce();
                }
                else if (lastMeasurement.getForce() < testContext.getLowerLimit()) {
                    testContext.sendSignal(2);
                    maxValue = lastMeasurement.getForce();
                }

                measurements.forEach(measurement -> {
                    try {
                        if(minValue > measurement.getForce()){
                            minValue = measurement.getForce();
                        }

                        if(maxValue < measurement.getForce()){
                            maxValue = measurement.getForce();
                        }

                        writer.write(measurement.getTimestamp() + "," + measurement.getForce());
                        writer.newLine();
                    } catch (IOException e) {
                        throw new RuntimeException("Failed to write to file stream", e);
                    }
                });

                wsMeasurements.addAll(measurements);
                if(System.currentTimeMillis() - wsMeasurements.getFirst().getTimestamp() > 60){
                    template.convertAndSend("/topic/updates", wsMeasurements);
                    wsMeasurements.clear();
                }
            }

        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            if (writer != null) {
                try {
                    writer.close();
                } catch (IOException e) {
                    throw new RuntimeException("Failed to close file stream", e);
                }
            }

            stream.stopReading();
        }
    }
}
