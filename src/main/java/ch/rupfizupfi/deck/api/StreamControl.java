package ch.rupfizupfi.deck.api;

import ch.rupfizupfi.deck.messaging.StatusThread;
import com.vaadin.flow.server.auth.AnonymousAllowed;
import com.vaadin.hilla.Endpoint;

@Endpoint
@AnonymousAllowed
public class StreamControl {
    protected StatusThread statusThread;

    StreamControl(StatusThread statusThread) {
        this.statusThread = statusThread;
    }

    public boolean start() {
        this.statusThread.startThread();
        return this.statusThread.isStarted();
    }

    public void stop() {
        this.statusThread.stopThread();
    }
}
