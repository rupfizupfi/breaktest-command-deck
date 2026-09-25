package ch.rupfizupfi.deck.device.relayswitch;

import com.fazecast.jSerialComm.SerialPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.OutputStream;

/**
 * Relay board on a plain serial port, outside the {@code device.api} plugin contract. Every command
 * reports whether its byte reached the port, so a caller can act on an unopened or unwritable one.
 */
public class FourWayRelaySwitch implements RelaySwitch {
    private static final Logger log = LoggerFactory.getLogger(FourWayRelaySwitch.class);

    private final SerialPort serialPort;
    private boolean isConnected;

    /**
     * Binds to the first port whose descriptive name contains {@code portDescription}, which names a
     * USB-serial chipset rather than the relay itself - see {@code device.relay.port-description}.
     */
    public FourWayRelaySwitch(String portDescription) throws ComportNotFoundException {
        this(SerialPort.getCommPort(getComPort(portDescription)));
    }

    FourWayRelaySwitch(SerialPort port) {
        serialPort = port;
        serialPort.setComPortParameters(115200, 8, SerialPort.ONE_STOP_BIT, SerialPort.NO_PARITY);
        serialPort.setComPortTimeouts(SerialPort.TIMEOUT_READ_SEMI_BLOCKING, 0, 0);
        isConnected = false;
    }

    private static String getComPort(String portDescription) throws ComportNotFoundException {
        SerialPort[] ports = SerialPort.getCommPorts();
        for (SerialPort port : ports) {
            if (port.getDescriptivePortName().contains(portDescription)) {
                return port.getSystemPortName();
            }
        }

        for (SerialPort port : ports) {
            log.warn("available port: {} - {} at {}", port.getSystemPortName(), port.getDescriptivePortName(),
                    port.getSystemPortPath());
        }

        throw new ComportNotFoundException("Four way switch com port matching '" + portDescription + "' not found");
    }

    @Override
    public boolean connect() {
        if (serialPort.openPort()) {
            log.info("Relay port {} opened.", serialPort.getSystemPortName());
            isConnected = true;
            return true;
        }

        log.warn("Failed to open relay port {}.", serialPort.getSystemPortName());
        return false;
    }

    @Override
    public void disconnect() {
        if (isConnected) {
            serialPort.closePort();
            isConnected = false;
        }
    }

    /** Whether the on-byte reached the port. */
    @Override
    public boolean enableRelay1() {
        return sendCommand(1);
    }

    /** Whether the off-byte reached the port. */
    @Override
    public boolean disableRelay1() {
        return sendCommand(0);
    }

    private boolean sendCommand(int command) {
        if (command != 0 && command != 1) {
            throw new IllegalArgumentException("Invalid command. Only 0 or 1 are allowed.");
        }

        if (!isConnected) {
            log.warn("Relay command {} dropped: port not connected.", command);
            return false;
        }

        try {
            OutputStream output = serialPort.getOutputStream();
            output.write(Integer.toString(command).getBytes());
            output.flush();
            return true;
        } catch (Exception e) {
            log.warn("Relay command {} not written.", command, e);
            return false;
        }
    }

    @Override
    public boolean isConnected() {
        return isConnected;
    }
}
