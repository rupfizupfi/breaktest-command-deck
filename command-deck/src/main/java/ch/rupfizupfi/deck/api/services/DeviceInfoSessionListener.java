package ch.rupfizupfi.deck.api.services;

import jakarta.servlet.http.HttpSessionEvent;
import jakarta.servlet.http.HttpSessionIdListener;
import jakarta.servlet.http.HttpSessionListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Releases the info-broadcast holds of an ended HTTP session (logout or idle timeout), so a tab
 * closed without its cleanup cannot keep the USB sessions open forever. A class of its own because
 * {@link DeviceInfoService}'s public methods are browser endpoints.
 */
@Component
public class DeviceInfoSessionListener implements HttpSessionListener, HttpSessionIdListener {
    private static final Logger logger = LoggerFactory.getLogger(DeviceInfoSessionListener.class);

    private final DeviceInfoService deviceInfoService;

    public DeviceInfoSessionListener(DeviceInfoService deviceInfoService) {
        this.deviceInfoService = deviceInfoService;
    }

    @Override
    public void sessionDestroyed(HttpSessionEvent event) {
        // Logged, not thrown: a failing device close must not break the logout invalidating the session.
        try {
            deviceInfoService.releaseSession(event.getSession().getId());
        } catch (RuntimeException e) {
            logger.warn("Stopping info broadcasting for an ended session failed", e);
        }
    }

    /** Session fixation protection renames the session; the holds follow it. */
    @Override
    public void sessionIdChanged(HttpSessionEvent event, String oldSessionId) {
        deviceInfoService.renameSession(oldSessionId, event.getSession().getId());
    }
}
