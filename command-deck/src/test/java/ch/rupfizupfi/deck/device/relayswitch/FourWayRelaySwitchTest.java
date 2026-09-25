package ch.rupfizupfi.deck.device.relayswitch;

import com.fazecast.jSerialComm.SerialPort;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FourWayRelaySwitchTest {

    @Test
    void aPortThatDoesNotOpenReportsEveryCommandAsUnsent() {
        SerialPort port = mock(SerialPort.class);
        when(port.openPort()).thenReturn(false);
        FourWayRelaySwitch relay = new FourWayRelaySwitch(port);

        assertThat(relay.connect()).isFalse();
        assertThat(relay.isConnected()).isFalse();
        assertThat(relay.enableRelay1()).isFalse();
        verify(port, never()).getOutputStream();
    }

    @Test
    void anOpenPortCarriesTheRelayBytes() {
        SerialPort port = mock(SerialPort.class);
        ByteArrayOutputStream written = new ByteArrayOutputStream();
        when(port.openPort()).thenReturn(true);
        when(port.getOutputStream()).thenReturn(written);
        FourWayRelaySwitch relay = new FourWayRelaySwitch(port);

        assertThat(relay.connect()).isTrue();
        assertThat(relay.enableRelay1()).isTrue();
        assertThat(written.toString(StandardCharsets.US_ASCII)).isEqualTo("1");

        assertThat(relay.disableRelay1()).isTrue();
        assertThat(written.toString(StandardCharsets.US_ASCII)).isEqualTo("10");
    }

    @Test
    void aWriteThatThrowsReportsTheCommandAsUnsent() {
        SerialPort port = mock(SerialPort.class);
        when(port.openPort()).thenReturn(true);
        when(port.getOutputStream()).thenReturn(new OutputStream() {
            @Override
            public void write(int b) throws IOException {
                throw new IOException("port went away");
            }
        });
        FourWayRelaySwitch relay = new FourWayRelaySwitch(port);

        assertThat(relay.connect()).isTrue();
        assertThat(relay.enableRelay1()).isFalse();
    }
}
