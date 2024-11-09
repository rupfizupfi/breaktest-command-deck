package ch.rupfizupfi.deck.device.relayswitch;

import com.fazecast.jSerialComm.SerialPort;

public class FourWayRelaySwitch {
    private SerialPort serialPort;
    private boolean isConnected;

    public FourWayRelaySwitch() throws ComportNotFoundException {
        serialPort = SerialPort.getCommPort(getComPort());
        serialPort.setComPortParameters(115200, 8, SerialPort.ONE_STOP_BIT, SerialPort.NO_PARITY);
        serialPort.setComPortTimeouts(SerialPort.TIMEOUT_READ_SEMI_BLOCKING, 0, 0);
        isConnected = false;
    }

    protected String getComPort() throws ComportNotFoundException {
        SerialPort[] ports = SerialPort.getCommPorts();
        for (SerialPort port : ports) {
            if(port.getDescriptivePortName().contains("CH9102")){
                return port.getSystemPortName();
            }
        }

        System.out.println("Available Ports:");
        for (SerialPort port : ports) {
            System.out.println(port.getSystemPortName());
            System.out.println(port.getDescriptivePortName());
            System.out.println(port.getSystemPortPath());
        }

        throw new ComportNotFoundException("Four way switch com port not found");
    }

    public boolean connect() {
        if (serialPort.openPort()) {
            System.out.println("Port opened successfully.");
            isConnected = true;
            return true;
        } else {
            System.out.println("Failed to open port.");
            return false;
        }
    }

    public void disconnect() {
        if (isConnected && serialPort != null) {
            serialPort.closePort();
            isConnected = false;
            System.out.println("Port closed.");
        }
    }

    public void enableRelay1() {
        sendCommand(1);
    }

    public void disableRelay1() {
        sendCommand(0);
    }

    public void sendCommand(int command) {
        if (!isConnected) {
            System.out.println("Connection not established. Cannot send command.");
            return;
        }

        if (command != 0 && command != 1) {
            throw new IllegalArgumentException("Invalid command. Only 0 or 1 are allowed.");
        }

        try {
            String commandString = Integer.toString(command);
            serialPort.getOutputStream().write(commandString.getBytes());
            serialPort.getOutputStream().flush();
            System.out.println("Command sent: " + command);
        } catch (Exception e) {
            System.err.println("Error sending command: " + e.getMessage());
        }
    }

    public boolean isConnected() {
        return isConnected;
    }
}