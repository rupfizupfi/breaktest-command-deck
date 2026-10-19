package ch.rupfizupfi.deck.device;

import ch.rupfizupfi.deck.data.SettingRepository;
import ch.rupfizupfi.deck.device.api.DriveProvider;
import ch.rupfizupfi.deck.device.api.LoadCellStreamProvider;
import ch.rupfizupfi.deck.device.frequencyinverter.DeviceInfoBroadcaster;
import ch.rupfizupfi.deck.device.frequencyinverter.FrequencyInverterDevice;
import ch.rupfizupfi.deck.device.loadcell.ForceBroadcaster;
import ch.rupfizupfi.deck.device.loadcell.LoadCellDevice;
import ch.rupfizupfi.deck.device.loadcell.SessionAwareStreamProvider;
import org.springframework.beans.factory.ObjectProvider;
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

    /**
     * @param sessionAware the same bean as {@code loadCellStreamProvider} when that bean also tells a
     *                     reconnect from a first open (the simulator); absent for a driver
     */
    public DeviceService(SimpMessagingTemplate template, SettingRepository settingRepository,
                         DriveProvider driveProvider, LoadCellStreamProvider loadCellStreamProvider,
                         ObjectProvider<SessionAwareStreamProvider> sessionAware) {
        this.settingRepository = settingRepository;
        frequencyInverter = new FrequencyInverterDevice(driveProvider);
        loadCell = new LoadCellDevice(sessionAwareView(loadCellStreamProvider, sessionAware.getIfAvailable()));
        loadCell.registerObserver(new ForceBroadcaster(template));
        deviceInfoBroadcaster = new DeviceInfoBroadcaster(template);
    }

    /**
     * A driver's provider opens the same way on a reconnect, so it is adapted with the flag ignored.
     * A session-aware bean must be the provider itself: a different one would open streams the
     * resolved provider never sees, so that wiring is refused at startup.
     */
    static SessionAwareStreamProvider sessionAwareView(LoadCellStreamProvider provider,
                                                       SessionAwareStreamProvider sessionAware) {
        if (sessionAware == null) {
            return reconnect -> provider.open();
        }
        if (sessionAware != provider) {
            throw new IllegalStateException("the session-aware load cell provider "
                    + sessionAware.getClass().getName() + " is not the load cell provider in use, "
                    + provider.getClass().getName());
        }
        return sessionAware;
    }

    public FrequencyInverterDevice getFrequencyInverter() {
        return frequencyInverter;
    }

    public LoadCellDevice getLoadCell() {
        return loadCell;
    }

    /** All or nothing: a drive that fails to open releases the load cell reference again. */
    public void enableInfoBroadcasting() {
        loadCell.connect();
        try {
            frequencyInverter.connect();
        } catch (Throwable e) {
            // Throwable: a pre-1.4.0 drive plugin can let an undeclared checked exception out of open().
            try {
                loadCell.disconnect();
            } catch (Throwable release) {
                e.addSuppressed(release);
            }
            throw e;
        }
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
