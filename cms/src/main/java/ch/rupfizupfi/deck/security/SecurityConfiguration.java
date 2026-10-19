package ch.rupfizupfi.deck.security;

import com.vaadin.flow.spring.security.RequestUtil;
import com.vaadin.flow.spring.security.VaadinSecurityConfigurer;
import com.vaadin.hilla.route.RouteUtil;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authorization.AuthenticatedAuthorizationManager;
import org.springframework.security.authorization.AuthorityAuthorizationManager;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;

import java.util.Set;
import java.util.function.Function;

@EnableWebSecurity
@Configuration
public class SecurityConfiguration {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http, RequestUtil requestUtil,
                                           ObjectProvider<RouteUtil> routeUtil) throws Exception {
        // No CSRF exemptions of our own: <vaadin-upload> sends the token header (util/csrf.ts), so a
        // session cookie alone cannot post a file. VaadinSecurityConfigurer still exempts the login
        // form and Vaadin's internal requests.

        // Public access. Only the hardware button box calls /api/** without a session; any other
        // /api path falls through to Vaadin's closing denyAll().
        http.authorizeHttpRequests(authorize -> authorize
                .requestMatchers( PathPatternRequestMatcher.withDefaults().matcher("/tests")).permitAll()
                .requestMatchers( PathPatternRequestMatcher.withDefaults().matcher("/images/*.png")).permitAll()
                .requestMatchers( PathPatternRequestMatcher.withDefaults().matcher("/line-awesome/**")).permitAll()
                .requestMatchers( PathPatternRequestMatcher.withDefaults().matcher("/api/ControllerEndpoint/**")).permitAll()
                .requestMatchers( PathPatternRequestMatcher.withDefaults().matcher("/api/files/**")).authenticated()
                .requestMatchers( PathPatternRequestMatcher.withDefaults().matcher("/api/DownloadEndpoint/**")).authenticated()
        );

        // The STOMP handshake needs its own rule: VaadinSecurityConfigurer ends the chain with
        // anyRequest().denyAll(). Authenticated, not permitAll: telemetry is for operators only.
        http.authorizeHttpRequests(authorize -> authorize
                .requestMatchers( PathPatternRequestMatcher.withDefaults().matcher("/status")).authenticated()
        );

        // Vaadin matches a Hilla view's rolesAllowed against authorities verbatim; this rule, ahead
        // of Vaadin's, adds the ROLE_ prefix so rolesAllowed can name Role enum values.
        http.authorizeHttpRequests(authorize -> authorize
                .requestMatchers(requestUtil::isSecuredHillaRoute)
                .access(hillaRouteAccess(request -> {
                    RouteUtil routes = routeUtil.getIfAvailable();
                    return routes == null ? Set.<String>of() : routes.getAllowedAuthorities(request);
                }))
        );

        return http
                .with(VaadinSecurityConfigurer.vaadin(), vaadin -> vaadin.loginView("/login"))
                .build();
    }

    /**
     * Grants a Hilla route request to any authority in {@code allowedRoles}, prefixed with
     * {@code ROLE_} unless already present, and to any authenticated user when the route names
     * no role.
     */
    static AuthorizationManager<RequestAuthorizationContext> hillaRouteAccess(
            Function<HttpServletRequest, Set<String>> allowedRoles) {
        return (authentication, context) -> {
            Set<String> roles = allowedRoles.apply(context.getRequest());
            if (roles.isEmpty()) {
                return AuthenticatedAuthorizationManager.<RequestAuthorizationContext>authenticated()
                        .authorize(authentication, context);
            }
            String[] authorities = roles.stream()
                    .map(role -> role.startsWith("ROLE_") ? role : "ROLE_" + role)
                    .toArray(String[]::new);
            return AuthorityAuthorizationManager.<RequestAuthorizationContext>hasAnyAuthority(authorities)
                    .authorize(authentication, context);
        };
    }

}
