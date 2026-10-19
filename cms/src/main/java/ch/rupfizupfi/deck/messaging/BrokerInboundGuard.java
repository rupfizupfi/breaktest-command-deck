package ch.rupfizupfi.deck.messaging;

import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.security.access.AccessDeniedException;

/**
 * Guards the client inbound channel: browsers only listen, the server alone publishes.
 *
 * <p>Without it the simple broker relays a client's SEND to {@code /topic/test-state} or
 * {@code /topic/load-cell} to every subscriber, so any logged-in user could forge the safety
 * display. Server-side publishing goes through {@code SimpMessagingTemplate} on the broker
 * channel and never passes here.
 *
 * <p>A refusal throws, which the STOMP handler answers with an ERROR frame.
 */
public class BrokerInboundGuard implements ChannelInterceptor {

    static final String TOPIC_PREFIX = "/topic/";

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        SimpMessageHeaderAccessor accessor = SimpMessageHeaderAccessor.wrap(message);
        switch (accessor.getMessageType()) {
            case CONNECT -> requireUser(accessor);
            case SUBSCRIBE -> {
                requireUser(accessor);
                String destination = accessor.getDestination();
                if (destination == null || !destination.startsWith(TOPIC_PREFIX)) {
                    throw new AccessDeniedException("SUBSCRIBE is allowed under " + TOPIC_PREFIX + " only");
                }
            }
            // Every SEND is refused: no @MessageMapping exists, so nothing under /app has a handler.
            // A future one must open its destination here deliberately, not inherit a prefix rule.
            case MESSAGE -> throw new AccessDeniedException("Clients may not send; topics are server-published");
            case UNSUBSCRIBE, DISCONNECT, HEARTBEAT -> { }
            case null, default -> throw new AccessDeniedException("Unsupported STOMP frame");
        }
        return message;
    }

    private static void requireUser(SimpMessageHeaderAccessor accessor) {
        if (accessor.getUser() == null) {
            throw new AccessDeniedException("Not authenticated");
        }
    }
}
