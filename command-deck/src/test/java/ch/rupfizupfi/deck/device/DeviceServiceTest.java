package ch.rupfizupfi.deck.device;

import ch.rupfizupfi.deck.data.SettingRepository;
import ch.rupfizupfi.deck.device.api.Drive;
import ch.rupfizupfi.deck.device.api.DriveProvider;
import ch.rupfizupfi.deck.device.api.LoadCellStream;
import ch.rupfizupfi.deck.device.api.LoadCellStreamProvider;
import ch.rupfizupfi.deck.device.loadcell.SessionAwareStreamProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/** How {@link DeviceService} wires its devices: the load cell's provider type (A-13), the info poll's hold. */
class DeviceServiceTest {

    @Test
    @Timeout(20)
    void theDashboardPollKeepsItsDriveHandleWhenARunEndsAfterASafeStop() {
        // OQ-68: tier 2 marks the handle lost; the run's later disconnect used to reach zero and
        // close the handle the dashboard poll was re-using.
        List<Drive> handles = new ArrayList<>();
        DriveProvider drives = () -> {
            Drive handle = mock(Drive.class);
            handles.add(handle);
            return handle;
        };
        @SuppressWarnings("unchecked")
        ObjectProvider<SessionAwareStreamProvider> noSessionAware = mock(ObjectProvider.class);
        var service = new DeviceService(mock(SimpMessagingTemplate.class), mock(SettingRepository.class),
                drives, () -> mock(LoadCellStream.class), noSessionAware);
        var inverter = service.getFrequencyInverter();

        service.enableInfoBroadcasting();
        try {
            inverter.invalidateConnection();
            assertThat(inverter.isDriveHandleOpen()).isFalse();

            inverter.connect(); // RunDevices.connectDrive()
            inverter.disconnect(); // RunDevices.destroy()

            assertThat(inverter.isConnected()).isTrue();
            assertThat(inverter.isDriveHandleOpen()).isTrue();
            assertThat(handles).hasSize(2);
            verify(handles.getFirst()).close();
            verify(handles.getLast(), never()).close();
        } finally {
            service.disableInfoBroadcasting();
        }
        assertThat(inverter.isDriveHandleOpen()).isFalse();
        verify(handles.getLast()).close();
    }

    @Test
    @Timeout(20)
    void aDriveThatFailsToOpenReleasesTheLoadCellReferenceAgain() {
        @SuppressWarnings("unchecked")
        ObjectProvider<SessionAwareStreamProvider> noSessionAware = mock(ObjectProvider.class);
        var service = new DeviceService(mock(SimpMessagingTemplate.class), mock(SettingRepository.class),
                () -> {
                    throw new IllegalStateException("no drive on the bus");
                }, () -> mock(LoadCellStream.class), noSessionAware);

        assertThatThrownBy(service::enableInfoBroadcasting).hasMessageContaining("no drive on the bus");
        assertThat(service.getLoadCell().isConnected()).isFalse();
        assertThat(service.getFrequencyInverter().isConnected()).isFalse();
    }

    @Test
    @Timeout(20)
    void aDriveThatThrowsAnUndeclaredCheckedExceptionStillReleasesTheLoadCellReference() {
        // A drive plugin built before device-api 1.4.0 can let a checked exception out of open().
        @SuppressWarnings("unchecked")
        ObjectProvider<SessionAwareStreamProvider> noSessionAware = mock(ObjectProvider.class);
        var service = new DeviceService(mock(SimpMessagingTemplate.class), mock(SettingRepository.class),
                () -> sneakyThrow(new java.io.IOException("USB transfer failed")),
                () -> mock(LoadCellStream.class), noSessionAware);

        assertThatThrownBy(service::enableInfoBroadcasting)
                .isInstanceOf(java.io.IOException.class)
                .hasMessageContaining("USB transfer failed");
        assertThat(service.getLoadCell().isConnected()).isFalse();
        assertThat(service.getFrequencyInverter().isConnected()).isFalse();
    }

    @SuppressWarnings("unchecked")
    private static <T, E extends Throwable> T sneakyThrow(Throwable t) throws E {
        throw (E) t;
    }

    @Test
    void aDriversPlainProviderIsAdaptedAndOpensTheSameWayOnAReconnect() {
        LoadCellStream stream = mock(LoadCellStream.class);
        List<String> opens = new ArrayList<>();
        LoadCellStreamProvider driver = () -> {
            opens.add("open");
            return stream;
        };

        SessionAwareStreamProvider view = DeviceService.sessionAwareView(driver, null);

        assertThat(view.open(false)).isSameAs(stream);
        assertThat(view.open(true)).isSameAs(stream);
        assertThat(opens).hasSize(2);
    }

    @Test
    void aSessionAwareProviderIsUsedAsItIs() {
        SessionAwareProvider simulator = new SessionAwareProvider();

        assertThat(DeviceService.sessionAwareView(simulator, simulator)).isSameAs(simulator);
    }

    @Test
    void aSessionAwareBeanThatIsNotTheProviderInUseIsRefused() {
        // It would open streams behind the resolved provider's back, e.g. past a decorating @Primary.
        LoadCellStreamProvider inUse = () -> mock(LoadCellStream.class);

        assertThatThrownBy(() -> DeviceService.sessionAwareView(inUse, new SessionAwareProvider()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not the load cell provider in use");
    }

    private static final class SessionAwareProvider implements LoadCellStreamProvider, SessionAwareStreamProvider {
        @Override
        public LoadCellStream open() {
            return open(false);
        }

        @Override
        public LoadCellStream open(boolean reconnect) {
            return mock(LoadCellStream.class);
        }
    }
}
