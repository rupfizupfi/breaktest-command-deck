package ch.rupfizupfi.deck.api.services;

import ch.rupfizupfi.deck.device.DeviceService;
import jakarta.servlet.http.HttpSession;
import jakarta.servlet.http.HttpSessionEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Per-client holds on the info broadcast (OQ-70). */
class DeviceInfoServiceTest {
    private DeviceService deviceService;
    private String session;
    private DeviceInfoService service;

    @BeforeEach
    void setUp() {
        deviceService = mock(DeviceService.class);
        session = "s1";
        service = new DeviceInfoService(deviceService, () -> session);
    }

    @Test
    void broadcastingRunsUntilTheLastClientLeaves() {
        assertThat(service.enable("a")).isTrue();
        assertThat(service.enable("b")).isTrue();
        verify(deviceService, times(1)).enableInfoBroadcasting();

        assertThat(service.disable("a")).isTrue();
        verify(deviceService, never()).disableInfoBroadcasting();
        assertThat(service.isEnabled()).isTrue();

        assertThat(service.disable("b")).isTrue();
        verify(deviceService, times(1)).disableInfoBroadcasting();
        assertThat(service.isEnabled()).isFalse();
    }

    @Test
    void aClientEnablingTwiceHoldsOneReference() {
        assertThat(service.enable("a")).isTrue();
        assertThat(service.enable("a")).isFalse();

        service.disable("a");

        verify(deviceService, times(1)).enableInfoBroadcasting();
        verify(deviceService, times(1)).disableInfoBroadcasting();
        assertThat(service.isEnabled()).isFalse();
    }

    @Test
    void anEndedSessionReleasesItsClientsHolds() {
        service.enable("a");
        session = "s2";
        service.enable("b");

        service.releaseSession("s1");
        verify(deviceService, never()).disableInfoBroadcasting();
        assertThat(service.isEnabled()).isTrue();

        service.releaseSession("unknown");
        service.releaseSession("s2");
        verify(deviceService, times(1)).disableInfoBroadcasting();
        assertThat(service.isEnabled()).isFalse();

        service.releaseSession("s2");
        verify(deviceService, times(1)).disableInfoBroadcasting();
    }

    @Test
    void theSessionListenerReleasesTheDestroyedSession() {
        service.enable("a");
        HttpSession httpSession = mock(HttpSession.class);
        when(httpSession.getId()).thenReturn("s1");

        new DeviceInfoSessionListener(service).sessionDestroyed(new HttpSessionEvent(httpSession));

        verify(deviceService, times(1)).disableInfoBroadcasting();
        assertThat(service.isEnabled()).isFalse();
    }

    @Test
    void holdsFollowARenamedSession() {
        service.enable("a");
        HttpSession httpSession = mock(HttpSession.class);
        when(httpSession.getId()).thenReturn("s9");
        var listener = new DeviceInfoSessionListener(service);

        listener.sessionIdChanged(new HttpSessionEvent(httpSession), "s1");
        service.releaseSession("s1");
        assertThat(service.isEnabled()).isTrue();

        listener.sessionDestroyed(new HttpSessionEvent(httpSession));
        assertThat(service.isEnabled()).isFalse();
    }

    @Test
    void aFailedOpenRecordsNoHold() {
        doThrow(new IllegalStateException("no drive on the bus")).when(deviceService).enableInfoBroadcasting();

        assertThatThrownBy(() -> service.enable("a")).hasMessageContaining("no drive on the bus");
        assertThat(service.isEnabled()).isFalse();
        assertThat(service.disable("a")).isFalse();
        verify(deviceService, never()).disableInfoBroadcasting();

        assertThatThrownBy(() -> service.enable("a")).isInstanceOf(IllegalStateException.class);
        verify(deviceService, times(2)).enableInfoBroadcasting();
    }

    @Test
    void disablingAClientWithoutAHoldChangesNothing() {
        service.enable("a");

        assertThat(service.disable("unknown")).isFalse();

        verify(deviceService, never()).disableInfoBroadcasting();
        assertThat(service.isEnabled()).isTrue();
    }

    @Test
    void aBlankClientIdIsRefused() {
        assertThatThrownBy(() -> service.enable(" ")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.disable(null)).isInstanceOf(IllegalArgumentException.class);
        verify(deviceService, never()).enableInfoBroadcasting();
    }
}
