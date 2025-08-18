package ch.rupfizupfi.deck.security;

import com.vaadin.flow.spring.security.VaadinWebSecurity;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatchers;

@EnableWebSecurity
@Configuration
public class SecurityConfiguration extends VaadinWebSecurity {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Override
    protected void configure(HttpSecurity http) throws Exception {
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

        super.configure(http);
        setLoginView(http, "/login");
    }

}
