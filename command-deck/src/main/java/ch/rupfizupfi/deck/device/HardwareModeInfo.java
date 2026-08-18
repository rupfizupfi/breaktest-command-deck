package ch.rupfizupfi.deck.device;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The resolved {@link HardwareMode} for this process, for the code that has to announce it.
 * <p>
 * Safe to read without re-validating: {@link HardwareModeCheck} has already refused the boot if the
 * value were unusable, so anything reaching this bean is a mode that is actually being served.
 */
@Component
public class HardwareModeInfo {

    private final HardwareMode mode;

    public HardwareModeInfo(@Value("${" + HardwareMode.PROPERTY + ":real}") String configured) {
        HardwareMode parsed = HardwareMode.parse(configured);
        this.mode = parsed == null ? HardwareMode.DEFAULT : parsed;
    }

    public HardwareMode mode() {
        return mode;
    }

    public boolean isSimulated() {
        return mode == HardwareMode.SIMULATED;
    }

    public String modeName() {
        return mode.propertyValue();
    }
}
