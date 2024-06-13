package ch.rupfizupfi.deck.testrunner;

import ch.rupfizupfi.dscusb.CellValueStream;
import ch.rupfizupfi.dscusb.Measurement;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

public class LoadCellThread implements Runnable   {
    private final SimpMessagingTemplate template;
    private volatile boolean running = false;
    private BufferedWriter writer;
    private CellValueStream stream;
    private TestContext testContext;

    LoadCellThread(SimpMessagingTemplate template, TestContext testContext) {
        this.template = template;
        this.testContext = testContext;
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
                var lastMeasurement = measurements.getLast();
                if (lastMeasurement.getForce() > testContext.getUpperLimit()) {
                    testContext.sendSignal(1);
                }
                if (lastMeasurement.getForce() < testContext.getLowerLimit()) {
                    testContext.sendSignal(2);
                }

                measurements.forEach(measurement -> {
                    try {
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

            if (writer != null) {
                try {
                    writer.close();
                } catch (IOException e) {
                    throw new RuntimeException("Failed to close file stream", e);
                }
            }
        } finally {
            stream.stopReading();
        }
    }
}
