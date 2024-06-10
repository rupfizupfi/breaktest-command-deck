package ch.rupfizupfi.deck.testrunner;

import ch.rupfizupfi.dscusb.CellValueStream;
import ch.rupfizupfi.dscusb.Measurement;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Paths;
import java.util.List;

public class TestRunnerThread implements Runnable {
    private final SimpMessagingTemplate template;
    private volatile boolean running = false;
    private BufferedWriter writer;
    private CellValueStream stream;
    private int testId;

    public TestRunnerThread(SimpMessagingTemplate template) {
        this.template = template;
    }

    @Override
    public void run() {
        String userHome = System.getProperty("user.home");
        String filePath = Paths.get(userHome, "breaktester", Integer.toString(this.testId), System.currentTimeMillis() + "_force.csv").toString();
        Paths.get(filePath).getParent().toFile().mkdirs();

        try {
            writer = new BufferedWriter(new FileWriter(filePath));
        } catch (IOException e) {
            throw new RuntimeException("Failed to create file stream", e);
        }

        stream = new CellValueStream();
        stream.startReading();

        try {
            while (running) {
                List<Measurement> measurements = stream.getNextValues();
                measurements.forEach(measurement -> {
                    try {
                        writer.write(measurement.toString());
                        writer.newLine();
                    } catch (IOException e) {
                        throw new RuntimeException("Failed to write to file stream", e);
                    }
                });

                template.convertAndSend("/topic/updates", measurements);
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

    public void startThread(int testId) {
        if (!running) {
            this.testId = testId;
            running = true;
            new Thread(this).start();
        }
    }

    public void stopThread() {
        running = false;
    }
}
