package ch.rupfizupfi.deck.messaging;

import org.junit.jupiter.api.Test;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;

import java.security.Principal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class BrokerInboundGuardTest {

    private static final Principal USER = UsernamePasswordAuthenticationToken.authenticated("user", null, null);

    private final BrokerInboundGuard guard = new BrokerInboundGuard();
    private final MessageChannel channel = mock(MessageChannel.class);

    @Test
    void connectWithUserPasses() {
        Message<?> message = frame(StompCommand.CONNECT, null, USER);
        assertThat(guard.preSend(message, channel)).isSameAs(message);
    }

    @Test
    void connectWithoutUserIsRefused() {
        assertRefused(frame(StompCommand.CONNECT, null, null));
    }

    @Test
    void subscribeToATopicWithUserPasses() {
        Message<?> message = frame(StompCommand.SUBSCRIBE, "/topic/load-cell", USER);
        assertThat(guard.preSend(message, channel)).isSameAs(message);
    }

    @Test
    void subscribeWithoutUserIsRefused() {
        assertRefused(frame(StompCommand.SUBSCRIBE, "/topic/load-cell", null));
    }

    @Test
    void subscribeOutsideTopicIsRefused() {
        assertRefused(frame(StompCommand.SUBSCRIBE, "/queue/x", USER));
        assertRefused(frame(StompCommand.SUBSCRIBE, "/app/x", USER));
        assertRefused(frame(StompCommand.SUBSCRIBE, "/topic", USER));
        assertRefused(frame(StompCommand.SUBSCRIBE, null, USER));
    }

    @Test
    void sendIsRefusedEvenWithUser() {
        assertRefused(frame(StompCommand.SEND, "/topic/test-state", USER));
        assertRefused(frame(StompCommand.SEND, "/app/x", USER));
    }

    @Test
    void transactionAndAckFramesAreRefused() {
        assertRefused(frame(StompCommand.BEGIN, null, USER));
        assertRefused(frame(StompCommand.ACK, null, USER));
    }

    @Test
    void unsubscribeAndDisconnectPass() {
        Message<?> unsubscribe = frame(StompCommand.UNSUBSCRIBE, null, USER);
        Message<?> disconnect = frame(StompCommand.DISCONNECT, null, null);
        assertThat(guard.preSend(unsubscribe, channel)).isSameAs(unsubscribe);
        assertThat(guard.preSend(disconnect, channel)).isSameAs(disconnect);
    }

    private void assertRefused(Message<?> message) {
        assertThatThrownBy(() -> guard.preSend(message, channel)).isInstanceOf(AccessDeniedException.class);
    }

    private static Message<?> frame(StompCommand command, String destination, Principal user) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(command);
        accessor.setSessionId("s1");
        if (destination != null) {
            accessor.setDestination(destination);
        }
        if (user != null) {
            accessor.setUser(user);
        }
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }
}
