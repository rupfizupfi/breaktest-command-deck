package ch.rupfizupfi.deck.device.vendor;

import ch.rupfizupfi.deck.device.api.Drive;
import ch.rupfizupfi.deck.device.api.DriveProvider;
import ch.rupfizupfi.usbmodbus.Cfw11;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Real-hardware {@link DriveProvider}. Present only when {@code lib/usbmodbus.jar} is on the
 * classpath <b>and</b> the mode is real — the condition mirrors the simulated provider's, so the
 * two can never both register and "simulated" can never resolve to hardware.
 */
@Component
@ConditionalOnProperty(name = "deck.hardware.mode", havingValue = "real", matchIfMissing = true)
public class Cfw11DriveProvider implements DriveProvider {

    @Override
    public Drive open() {
        return new Cfw11Drive(new Cfw11());
    }
}

