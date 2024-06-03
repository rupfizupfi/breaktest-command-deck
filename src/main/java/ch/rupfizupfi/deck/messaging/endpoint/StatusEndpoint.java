package ch.rupfizupfi.deck.messaging.endpoint;

import ch.rupfizupfi.deck.messaging.dto.Status;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.SendTo;
import org.springframework.stereotype.Controller;

@Controller
public class StatusEndpoint {
    @MessageMapping("/hello")
    @SendTo("/topic/system")
    public Status update() throws Exception {
        return new Status("Hello, World!");
    }
}
