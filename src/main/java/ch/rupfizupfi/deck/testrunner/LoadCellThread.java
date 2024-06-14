package ch.rupfizupfi.deck.testrunner;

import ch.rupfizupfi.dscusb.CellValueStream;
import ch.rupfizupfi.dscusb.Measurement;
import com.google.common.collect.EvictingQueue;
import org.apache.commons.io.input.buffer.CircularByteBuffer;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class LoadCellThread implements Runnable   {
    private final SimpMessagingTemplate template;
    private volatile boolean running = false;
    private BufferedWriter writer;
    private CellValueStream stream;
    private TestContext testContext;
    private volatile float minValue;
    private volatile float maxValue;

    LoadCellThread(SimpMessagingTemplate template, TestContext testContext) {
        this.template = template;
        this.testContext = testContext;
        minValue = (float) testContext.getLowerLimit();
        maxValue = (float) testContext.getUpperLimit();
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
        String userHome = System.getProperty("user.home");
        String filePath = Paths.get(userHome, "breaktester", Long.toString(this.testContext.getTestId()), System.currentTimeMillis() + "_force.csv").toString();
        Paths.get(filePath).getParent().toFile().mkdirs();

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

                        writer.write(measurement.toString());
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
