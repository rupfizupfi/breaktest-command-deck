package ch.rupfizupfi.deck.device.loadcell;

import ch.rupfizupfi.deck.device.Device;
import ch.rupfizupfi.dscusb.CellValueStream;
import ch.rupfizupfi.dscusb.Measurement;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Logger;

public class LoadCellDevice extends Device {
    Logger log = Logger.getLogger(LoadCellDevice.class.getName());

    private CellValueStream stream;
    private final List<MeasurementObserver> observers = new CopyOnWriteArrayList<>();
    private Thread dataThread;
    private volatile boolean isRunning = false;

    @Override
    protected void openConnection() {
        log.info("openConnection entered");
        stream = new CellValueStream();
        stream.startReading();
        isRunning = true;
        dataThread = new Thread(this::readData);
        dataThread.start();
    }

    @Override
    protected void closeConnection() {
        log.info("closeConnection entered");
        isRunning = false;
        if (dataThread != null) {
            try {
                dataThread.join(); // Wait for the thread to finish
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            dataThread = null;
            log.info("dataThread joined");
        }
        if (stream != null) {
            stream.stopReading();
            stream = null;
        }

        log.info("closeConnection finished");
    }

    @Override
    public CellValueStream getHardwareComponent() {
        return stream;
    }

    public void registerObserver(MeasurementObserver observer) {
        observers.add(observer);
    }

    public void unregisterObserver(MeasurementObserver observer) {
        observers.remove(observer);
    }

    private void notifyObservers(List<Measurement> measurements) {
        for (MeasurementObserver observer : observers) {
            observer.update(measurements);
        }
    }

    private void readData() {
        log.info("readData entered");

        try {
            while (isRunning) {
                var measurements = stream.getNextValues();
                if (!measurements.isEmpty()) {
                    notifyObservers(measurements);
                }

                Thread.sleep(20);
            }
        } catch (InterruptedException ignored) {
        }

        log.info("readData finished");
    }
}