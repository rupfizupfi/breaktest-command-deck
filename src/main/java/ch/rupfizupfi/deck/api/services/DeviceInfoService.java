package ch.rupfizupfi.deck.api.services;

import ch.rupfizupfi.deck.device.DeviceService;
import com.vaadin.hilla.BrowserCallable;
import jakarta.annotation.security.PermitAll;

@BrowserCallable
@PermitAll
public class DeviceInfoService {
    private final DeviceService deviceService;
    protected boolean isEnabled = false;

    public DeviceInfoService(DeviceService deviceService) {
        this.deviceService = deviceService;
    }

    public boolean enable() {
        if (isEnabled) {
            return false;
        }

        isEnabled = true;
        deviceService.enableInfoBroadcasting();
        return true;
    }

    public boolean isEnabled() {
        return isEnabled;
    }

    public boolean disable() {
        if (!isEnabled) {
            return false;
        }

        isEnabled = false;
        deviceService.disableInfoBroadcasting();
        return true;
    }
}
