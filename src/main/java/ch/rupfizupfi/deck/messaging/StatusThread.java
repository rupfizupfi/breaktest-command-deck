package ch.rupfizupfi.deck.messaging;

import ch.rupfizupfi.dscusb.CellValueStream;
import ch.rupfizupfi.dscusb.Measurement;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class StatusThread implements Runnable {

    private final SimpMessagingTemplate template;
    private volatile boolean running = false;
    private Thread thread;

    public StatusThread(SimpMessagingTemplate template) {
        this.template = template;
    }

    public void startThread() {
        if (thread != null && thread.isAlive()) {
            return;
        }

        running = true;
        thread = new Thread(this);
        thread.start();
    }

    public boolean isStarted() {
        return thread != null && thread.isAlive();
    }

    @Override
    public void run() {
        CellValueStream stream = new CellValueStream();
        stream.startReading();

        while (running) {
            try {
                Thread.sleep(200);
                List<Measurement> measurements = stream.getNextValues();
                template.convertAndSend("/topic/updates", measurements);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(e);
            }
        }

        stream.stopReading();
    }

    public void stopThread() {
        this.running = false;
    }
}