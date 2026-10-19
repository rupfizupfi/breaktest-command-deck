package ch.rupfizupfi.deck.api.services;

import ch.rupfizupfi.deck.device.HardwareModeInfo;
import com.vaadin.flow.server.auth.AnonymousAllowed;
import com.vaadin.hilla.BrowserCallable;

/**
 * Tells the browser whether it is looking at real hardware.
 * <p>
 * Anonymous, so the simulated banner shows before login too.
 */
@BrowserCallable
@AnonymousAllowed
public class HardwareModeService {

    private final HardwareModeInfo hardwareModeInfo;

    public HardwareModeService(HardwareModeInfo hardwareModeInfo) {
        this.hardwareModeInfo = hardwareModeInfo;
    }

    public HardwareModeResponse getMode() {
        return new HardwareModeResponse(hardwareModeInfo.modeName(), hardwareModeInfo.isSimulated());
    }

    public record HardwareModeResponse(String mode, boolean simulated) {
    }
}
