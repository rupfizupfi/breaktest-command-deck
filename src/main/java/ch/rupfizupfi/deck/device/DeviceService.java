package ch.rupfizupfi.deck.device;

import ch.rupfizupfi.deck.device.frequencyconverter.CFW11Device;
import ch.rupfizupfi.deck.device.frequencyconverter.DeviceInfoBroadcaster;
import ch.rupfizupfi.deck.device.loadcell.ForceBroadcaster;
import ch.rupfizupfi.deck.device.loadcell.LoadCellDevice;
import org.springframework.context.annotation.Scope;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

@Service
@Scope("singleton")
public class DeviceService {
    private final CFW11Device frequencyConverter;
    private final LoadCellDevice loadCell;
    private final DeviceInfoBroadcaster deviceInfoBroadcaster;

    public DeviceService(SimpMessagingTemplate template) {
        frequencyConverter = new CFW11Device();
        loadCell = new LoadCellDevice();
        loadCell.registerObserver(new ForceBroadcaster(template));
        deviceInfoBroadcaster = new DeviceInfoBroadcaster(template);
    }

    public CFW11Device getFrequencyConverter() {
        return frequencyConverter;
    }

    public LoadCellDevice getLoadCell() {
        return loadCell;
    }

    public void enableInfoBroadcasting() {
        loadCell.connect();
        frequencyConverter.connect();
        frequencyConverter.registerObserver(deviceInfoBroadcaster);
    }

    public void disableInfoBroadcasting() {
        loadCell.disconnect();
        frequencyConverter.unregisterObserver(deviceInfoBroadcaster);
        frequencyConverter.disconnect();
    }
}
