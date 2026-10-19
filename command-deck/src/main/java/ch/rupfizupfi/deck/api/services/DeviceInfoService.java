package ch.rupfizupfi.deck.api.services;

import ch.rupfizupfi.deck.device.DeviceService;
import com.vaadin.hilla.BrowserCallable;
import jakarta.annotation.security.PermitAll;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.context.request.RequestContextHolder;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Info broadcasting as per-client holds (OQ-70): the first hold starts it, the last release stops
 * it, so one tab leaving never blinds another. A client is one view in one page load, named by an
 * id the frontend draws for it. A hold whose tab closed without releasing it lasts until its HTTP
 * session ends ({@link DeviceInfoSessionListener}).
 */
@BrowserCallable
@PermitAll
public class DeviceInfoService {
    private final DeviceService deviceService;
    private final Supplier<String> currentSessionId;
    /** Client id to the HTTP session that holds it. Guarded by {@code this}. */
    private final Map<String, String> holds = new HashMap<>();

    @Autowired
    public DeviceInfoService(DeviceService deviceService) {
        this(deviceService, () -> RequestContextHolder.currentRequestAttributes().getSessionId());
    }

    DeviceInfoService(DeviceService deviceService, Supplier<String> currentSessionId) {
        this.deviceService = deviceService;
        this.currentSessionId = currentSessionId;
    }

    /**
     * @return true when this call took a new hold, false when the client already held one
     */
    public synchronized boolean enable(String clientId) {
        requireClientId(clientId);
        String sessionId = currentSessionId.get();
        if (holds.containsKey(clientId)) {
            holds.put(clientId, sessionId);
            return false;
        }

        if (holds.isEmpty()) {
            // Throws before the hold is recorded, so a failed open leaves nothing to release.
            deviceService.enableInfoBroadcasting();
        }
        holds.put(clientId, sessionId);
        return true;
    }

    public synchronized boolean isEnabled() {
        return !holds.isEmpty();
    }

    /**
     * @return true when the client held a hold and released it
     */
    public synchronized boolean disable(String clientId) {
        requireClientId(clientId);
        if (holds.remove(clientId) == null) {
            return false;
        }

        stopWhenUnheld();
        return true;
    }

    /** Package-private: every public method of a {@code @BrowserCallable} is an endpoint. */
    synchronized void releaseSession(String sessionId) {
        if (holds.values().removeIf(sessionId::equals)) {
            stopWhenUnheld();
        }
    }

    synchronized void renameSession(String oldSessionId, String newSessionId) {
        holds.replaceAll((client, session) -> session.equals(oldSessionId) ? newSessionId : session);
    }

    private void stopWhenUnheld() {
        if (holds.isEmpty()) {
            deviceService.disableInfoBroadcasting();
        }
    }

    private static void requireClientId(String clientId) {
        if (clientId == null || clientId.isBlank()) {
            throw new IllegalArgumentException("clientId must not be blank");
        }
    }
}
