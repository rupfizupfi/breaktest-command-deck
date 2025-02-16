package ch.rupfizupfi.deck.device.frequencyconverter;

import org.springframework.messaging.simp.SimpMessagingTemplate;

public class DeviceInfoBroadcaster implements InfoObserver {
    private final SimpMessagingTemplate template;

    public DeviceInfoBroadcaster(SimpMessagingTemplate template) {
        this.template = template;
    }

    @Override
    public void update(Info info) {
        template.convertAndSend("/topic/frequency-converter-info", info);
    }
}
