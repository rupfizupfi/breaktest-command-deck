package ch.rupfizupfi.deck.api.rest;

import com.vaadin.flow.server.auth.AnonymousAllowed;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import java.util.logging.Logger;

@RestController
@RequestMapping("/api/ControllerEndpoint")
@AnonymousAllowed
public class ControllerEndpoint {
    Logger log = Logger.getLogger(ControllerEndpoint.class.getName());

    @RequestMapping(value = "/press/{button}", method = RequestMethod.GET)
    public String onButtonPress(@PathVariable String button) {
        log.info("button pressed " + button);
        return "button pressed " + button;
    }
}
