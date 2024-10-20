package ch.rupfizupfi.deck.device.frequencyconverter;

import ch.rupfizupfi.deck.device.Device;
import ch.rupfizupfi.usbmodbus.Cfw11;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public class CFW11Device extends Device {
    private Cfw11 cfw11;
    private final List<InfoObserver> observers = new CopyOnWriteArrayList<>();
    private Thread dataThread;

    @Override
    protected void openConnection() {
        cfw11 = new Cfw11();
        tryStartThread();
    }

    protected synchronized void tryStartThread() {
        if (dataThread == null && !observers.isEmpty()) {
            dataThread = new Thread(this::readData);
            dataThread.start();
        }
    }

    protected synchronized void tryStopThread() {
        if (dataThread != null && observers.isEmpty()) {
            try {
                dataThread.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            dataThread = null;
        }
    }

    @Override
    protected void closeConnection() {
        getConnectionStatus().complete(false);

        tryStopThread();

        if (cfw11 != null) {
            cfw11.getUsbComm().closeUSBComm();
            cfw11 = null;
        }
    }

    @Override
    public Cfw11 getHardwareComponent() {
        return cfw11;
    }

    public void registerObserver(InfoObserver observer) {
        observers.add(observer);
        tryStartThread();
    }

    public void unregisterObserver(InfoObserver observer) {
        observers.remove(observer);
        tryStopThread();
    }

    private void notifyObservers(Info info) {
        for (InfoObserver observer : observers) {
            observer.update(info);
        }
    }

    private void readData() {
        while (!getConnectionStatus().isDone()) {
            var info = new Info();
            var controlParameters = cfw11.getControlParameters();
            info.start = controlParameters.get("start");
            info.generalEnable = controlParameters.get("generalEnable");
            info.useSecondRamp = controlParameters.get("useSecondRamp");
            info.directionIsForward = controlParameters.get("directionIsForward");
            info.speed = cfw11.getSpeedValueAsRpm() * 0.375;
            this.notifyObservers(info);

            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }
}
