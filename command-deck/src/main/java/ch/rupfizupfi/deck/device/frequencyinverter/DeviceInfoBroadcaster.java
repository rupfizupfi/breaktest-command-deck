package ch.rupfizupfi.deck.device.frequencyinverter;

import org.springframework.messaging.simp.SimpMessagingTemplate;

public class DeviceInfoBroadcaster implements InfoObserver {
    private final SimpMessagingTemplate template;

    public DeviceInfoBroadcaster(SimpMessagingTemplate template) {
        this.template = template;
    }

    @Override
    public void update(Info info) {
        template.convertAndSend("/topic/frequency-inverter-info", info);
    }
}
