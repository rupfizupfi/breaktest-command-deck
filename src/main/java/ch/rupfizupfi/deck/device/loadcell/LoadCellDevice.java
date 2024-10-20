package ch.rupfizupfi.deck.device.loadcell;

import ch.rupfizupfi.deck.device.Device;
import ch.rupfizupfi.dscusb.CellValueStream;
import ch.rupfizupfi.dscusb.Measurement;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public class LoadCellDevice extends Device {
    private CellValueStream stream;
    private final List<MeasurementObserver> observers = new CopyOnWriteArrayList<>();
    private Thread dataThread;

    @Override
    protected void openConnection() {
        stream = new CellValueStream();
        stream.startReading();
        dataThread = new Thread(this::readData);
        dataThread.start();
    }

    @Override
    protected void closeConnection() {
        getConnectionStatus().complete(false);
        if (dataThread != null) {
            try {
                dataThread.join(); // Wait for the thread to finish
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            dataThread = null;
        }
        if (stream != null) {
            stream.stopReading();
            stream = null;
        }
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
        while (!getConnectionStatus().isDone()) {
            notifyObservers(stream.getNextValues());
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }
}