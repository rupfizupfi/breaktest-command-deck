package ch.rupfizupfi.deck.api.services;

import ch.rupfizupfi.deck.device.DeviceService;
import com.vaadin.hilla.BrowserCallable;
import jakarta.annotation.security.PermitAll;

@BrowserCallable
@PermitAll
public class DeviceInfoService {
    private final DeviceService deviceService;


    public DeviceInfoService(DeviceService deviceService) {
        this.deviceService = deviceService;
    }

    public boolean enable() {
        deviceService.enableInfoBroadcasting();
        return true;
    }

    public boolean disable() {
        deviceService.disableInfoBroadcasting();
        return true;
    }
}
