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

    /**
     * Vaadin 25 removed {@code VaadinWebSecurity}; the Vaadin defaults are now contributed
     * by {@link VaadinSecurityConfigurer} into an application-owned filter chain bean.
     */
    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http, RequestUtil requestUtil,
                                           ObjectProvider<RouteUtil> routeUtil) throws Exception {
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

        // Vaadin 25.2 matches a Hilla view's rolesAllowed against granted authorities verbatim.
        // Registered ahead of Vaadin's own rule, this one applies the ROLE_ prefix the way
        // isUserInRole does, so rolesAllowed keeps naming Role enum values.
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
