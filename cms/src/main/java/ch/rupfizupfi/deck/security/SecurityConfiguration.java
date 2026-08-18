package ch.rupfizupfi.deck.security;

import com.vaadin.flow.spring.security.VaadinSecurityConfigurer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;

@EnableWebSecurity
@Configuration
public class SecurityConfiguration {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /**
     * Vaadin 25 removed {@code VaadinWebSecurity}; the Vaadin defaults are now contributed
     * by {@link VaadinSecurityConfigurer} into an application-owned filter chain bean.
     */
    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        // Disable CSRF protection for specific endpoints
        http.csrf(csrf -> csrf.ignoringRequestMatchers(
                PathPatternRequestMatcher.withDefaults().matcher("/api/files/uploads"),
                PathPatternRequestMatcher.withDefaults().matcher("/api/files/upload")
        ));

        // Public access
        http.authorizeHttpRequests(authorize -> authorize
                .requestMatchers( PathPatternRequestMatcher.withDefaults().matcher("/tests")).permitAll()
                .requestMatchers( PathPatternRequestMatcher.withDefaults().matcher("/images/*.png")).permitAll()
                .requestMatchers( PathPatternRequestMatcher.withDefaults().matcher("/line-awesome/**")).permitAll()
                .requestMatchers( PathPatternRequestMatcher.withDefaults().matcher("/api/**")).permitAll()
        );

        // The STOMP handshake is neither a Vaadin route nor a Hilla endpoint, and
        // VaadinSecurityConfigurer closes the chain with anyRequest().denyAll() — so without a
        // rule of its own the live telemetry socket is refused with 403 for a logged-in operator.
        // Authenticated, never permitAll: the topics carry one machine's telemetry, shared
        // between its operators by decision, not published.
        http.authorizeHttpRequests(authorize -> authorize
                .requestMatchers( PathPatternRequestMatcher.withDefaults().matcher("/status")).authenticated()
        );

        return http
                .with(VaadinSecurityConfigurer.vaadin(), vaadin -> vaadin.loginView("/login"))
                .build();
    }

}
