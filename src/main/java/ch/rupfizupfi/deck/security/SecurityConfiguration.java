package ch.rupfizupfi.deck.security;

import com.vaadin.flow.spring.security.VaadinWebSecurity;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;

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
                new AntPathRequestMatcher("/api/files/uploads"),
                new AntPathRequestMatcher("/api/files/upload")
        ));

        // Public access
        http.authorizeHttpRequests(authorize -> authorize
                .requestMatchers(new AntPathRequestMatcher("/tests")).permitAll()
                .requestMatchers(new AntPathRequestMatcher("/images/*.png")).permitAll()
                .requestMatchers(new AntPathRequestMatcher("/line-awesome/**/*.svg")).permitAll()
                .requestMatchers(new AntPathRequestMatcher("/api/**")).permitAll()
        );

        super.configure(http);
        setLoginView(http, "/login");
    }

}
