package ch.rupfizupfi.deck.device.vendor;

import ch.rupfizupfi.deck.device.api.LoadCellStream;
import ch.rupfizupfi.deck.device.api.Measurement;
import ch.rupfizupfi.deck.device.api.StreamFailure;
import ch.rupfizupfi.dscusb.CellValueStream;
import ch.rupfizupfi.dscusb.CommandExecutionException;

import java.util.List;

/**
 * {@link LoadCellStream} over the vendor {@code CellValueStream}. Pure delegation by rule â€” see
 * {@link Cfw11Drive}. The two mappings below carry no policy: they translate types only.
 */
public class CellValueStreamAdapter implements LoadCellStream {

    private final CellValueStream stream;

    public CellValueStreamAdapter(CellValueStream stream) {
        this.stream = stream;
    }

    @Override
    public void startReading() {
        stream.startReading();
    }

    @Override
    public void stopReading() {
        stream.stopReading();
    }

    @Override
    public List<Measurement> getNextValues() {
        return stream.getNextValues().stream()
                .map(m -> new Measurement(m.getForce(), m.getTimestamp()))
                .toList();
    }

    @Override
    public boolean isReading() {
        return stream.isReading();
    }

    @Override
    public StreamFailure lastError() {
        Throwable error = stream.getLastError();
        if (error == null) {
            return null;
        }

        // The driver's numeric code is kept verbatim: it maps onto the READCOMMAND table in the
        // vendor docs. Anything else carries no code at all, and null is what lets the deck tell the
        // two apart without this adapter having to word anything.
        String driverCode = error instanceof CommandExecutionException driverError
                ? String.valueOf(driverError.getErrorCode())
                : null;

        return new StreamFailure(driverCode, error.getClass().getSimpleName(), error.getMessage());
    }
}

