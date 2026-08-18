package ch.rupfizupfi.deck.device.vendor;

import ch.rupfizupfi.deck.device.api.LoadCellStream;
import ch.rupfizupfi.deck.device.api.LoadCellStreamProvider;
import ch.rupfizupfi.dscusb.CellValueStream;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Real-hardware {@link LoadCellStreamProvider}. Present only when {@code lib/dscusb.jar} is on the
 * classpath <b>and</b> the mode is real; see {@link Cfw11DriveProvider} for why the condition mirrors.
 */
@Component
@ConditionalOnProperty(name = "deck.hardware.mode", havingValue = "real", matchIfMissing = true)
public class CellValueStreamProvider implements LoadCellStreamProvider {

    @Override
    public LoadCellStream open() {
        return new CellValueStreamAdapter(new CellValueStream());
    }
}

