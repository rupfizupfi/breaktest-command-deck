package ch.rupfizupfi.deck.messaging;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.security.autoconfigure.web.servlet.SecurityFilterAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.tomcat.autoconfigure.servlet.TomcatServletWebServerAutoConfiguration;
import org.springframework.boot.webmvc.autoconfigure.DispatcherServletAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.converter.StringMessageConverter;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.SessionConnectEvent;
import org.springframework.web.socket.messaging.WebSocketStompClient;

import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.security.Principal;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Real handshake, real broker, real {@link WebSocketConfig}: a Spring Security–authenticated
 * handshake puts its principal on the STOMP session (else the guard would lock every operator out),
 * a SUBSCRIBE receives what the server publishes, and a client SEND to a topic is answered with an
 * ERROR frame instead of reaching subscribers.
 *
 * <p>Only the web server, security filter and messaging are booted — not the cms application, whose
 * Vaadin servlet would start a dev-mode frontend build. HTTP Basic stands in for the app's form
 * login: both reach the handshake as the {@code HttpServletRequest} principal Spring Security sets.
 */
@SpringBootTest(classes = BrokerInboundGuardIntegrationTest.Server.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class BrokerInboundGuardIntegrationTest {

    @Configuration
    @EnableWebSecurity
    @Import(WebSocketConfig.class)
    @ImportAutoConfiguration({
            TomcatServletWebServerAutoConfiguration.class,
            DispatcherServletAutoConfiguration.class,
            SecurityFilterAutoConfiguration.class
    })
    static class Server {

        final List<Principal> connectedUsers = new CopyOnWriteArrayList<>();

        @Bean
        SecurityFilterChain chain(HttpSecurity http) {
            return http
                    .authorizeHttpRequests(authorize -> authorize.requestMatchers("/status").authenticated())
                    .httpBasic(Customizer.withDefaults())
                    .csrf(csrf -> csrf.disable())
                    .build();
        }

        @Bean
        InMemoryUserDetailsManager users() {
            return new InMemoryUserDetailsManager(User.withUsername("operator").password("{noop}pw").roles("USER").build());
        }

        @EventListener
        void onConnect(SessionConnectEvent event) {
            if (event.getUser() != null) {
                connectedUsers.add(event.getUser());
            }
        }
    }

    @LocalServerPort
    int port;

    @Autowired
    SimpMessagingTemplate template;

    @Autowired
    Server server;

    private final WebSocketStompClient client = new WebSocketStompClient(new StandardWebSocketClient());
    private final List<StompSession> sessions = new CopyOnWriteArrayList<>();

    @AfterEach
    void disconnect() {
        sessions.forEach(session -> {
            if (session.isConnected()) {
                session.disconnect();
            }
        });
        client.stop();
    }

    @Test
    void authenticatedHandshakeCarriesThePrincipalIntoTheStompSession() throws Exception {
        connect(true, new StompSessionHandlerAdapter() { });

        assertThat(server.connectedUsers).extracting(Principal::getName).contains("operator");
    }

    @Test
    void anonymousHandshakeIsRefused() {
        int before = server.connectedUsers.size();
        assertThatThrownBy(() -> connect(false, new StompSessionHandlerAdapter() { }))
                .isInstanceOf(ExecutionException.class);
        assertThat(server.connectedUsers).hasSize(before);
    }

    @Test
    void subscriberGetsServerFramesButNeverAClientForgery() throws Exception {
        List<String> received = new CopyOnWriteArrayList<>();
        StompSession listener = connect(true, new StompSessionHandlerAdapter() { });
        listener.subscribe("/topic/test-state", new StompFrameHandler() {
            @Override
            public Type getPayloadType(StompHeaders headers) {
                return String.class;
            }

            @Override
            public void handleFrame(StompHeaders headers, Object payload) {
                received.add((String) payload);
            }
        });

        CompletableFuture<String> error = new CompletableFuture<>();
        StompSession forger = connect(true, new StompSessionHandlerAdapter() {
            @Override
            public void handleFrame(StompHeaders headers, Object payload) {
                error.complete(headers.getFirst("message"));
            }
        });
        forger.send("/topic/test-state", "forged");

        // The ERROR frame names the channel, not the reason: the guard's refusal is wrapped.
        assertThat(error.get(10, TimeUnit.SECONDS)).contains("clientInboundChannel");

        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!received.contains("real") && System.nanoTime() < deadline) {
            template.convertAndSend("/topic/test-state", "real");
            Thread.sleep(100);
        }
        assertThat(received).contains("real").doesNotContain("forged");
    }

    private StompSession connect(boolean authenticated, StompSessionHandlerAdapter handler) throws Exception {
        client.setMessageConverter(new StringMessageConverter());
        WebSocketHttpHeaders headers = new WebSocketHttpHeaders();
        if (authenticated) {
            headers.add("Authorization", "Basic "
                    + Base64.getEncoder().encodeToString("operator:pw".getBytes(StandardCharsets.UTF_8)));
        }
        StompSession session = client.connectAsync("ws://localhost:" + port + "/status", headers, handler)
                .get(10, TimeUnit.SECONDS);
        sessions.add(session);
        return session;
    }
}
