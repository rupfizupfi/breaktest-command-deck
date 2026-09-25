package ch.rupfizupfi.deck.device;

import ch.rupfizupfi.deck.data.SettingRepository;
import ch.rupfizupfi.deck.device.api.DriveProvider;
import ch.rupfizupfi.deck.device.api.LoadCellStreamProvider;
import ch.rupfizupfi.deck.device.frequencyinverter.DeviceInfoBroadcaster;
import ch.rupfizupfi.deck.device.frequencyinverter.FrequencyInverterDevice;
import ch.rupfizupfi.deck.device.loadcell.ForceBroadcaster;
import ch.rupfizupfi.deck.device.loadcell.LoadCellDevice;
import org.springframework.context.annotation.Scope;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

@Service
@Scope("singleton")
public class DeviceService {
    private final FrequencyInverterDevice frequencyInverter;
    private final LoadCellDevice loadCell;
    private final DeviceInfoBroadcaster deviceInfoBroadcaster;
    private final SettingRepository settingRepository;

    public DeviceService(SimpMessagingTemplate template, SettingRepository settingRepository,
                         DriveProvider driveProvider, LoadCellStreamProvider loadCellStreamProvider) {
        this.settingRepository = settingRepository;
        frequencyInverter = new FrequencyInverterDevice(driveProvider);
        loadCell = new LoadCellDevice(loadCellStreamProvider);
        loadCell.registerObserver(new ForceBroadcaster(template));
        deviceInfoBroadcaster = new DeviceInfoBroadcaster(template);
    }

    public FrequencyInverterDevice getFrequencyInverter() {
        return frequencyInverter;
    }

    public LoadCellDevice getLoadCell() {
        return loadCell;
    }

    public void enableInfoBroadcasting() {
        loadCell.connect();
        frequencyInverter.connect();
        frequencyInverter.registerObserver(deviceInfoBroadcaster);
    }

    public void disableInfoBroadcasting() {
        loadCell.disconnect();
        frequencyInverter.unregisterObserver(deviceInfoBroadcaster);
        frequencyInverter.disconnect();
    }

    public SettingRepository getSettingRepository() {
        return settingRepository;
    }
}
