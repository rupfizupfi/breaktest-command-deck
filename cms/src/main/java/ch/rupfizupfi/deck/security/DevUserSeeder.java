package ch.rupfizupfi.deck.security;

import ch.rupfizupfi.deck.data.Role;
import ch.rupfizupfi.deck.data.User;
import ch.rupfizupfi.deck.data.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * The well-known {@code user}/{@code user} and {@code admin}/{@code admin} accounts, for the dev
 * profile only: the migrations also run under {@code docker}, so they cannot live there.
 * Seeds an empty user table and nothing else; {@code deck.dev.seed-users=false} keeps it out.
 */
@Component
@Profile("dev")
public class DevUserSeeder implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(DevUserSeeder.class);

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final boolean enabled;

    public DevUserSeeder(UserRepository userRepository, PasswordEncoder passwordEncoder,
                         @Value("${deck.dev.seed-users:true}") boolean enabled) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.enabled = enabled;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!enabled || userRepository.count() > 0) {
            return;
        }
        userRepository.save(user("user", "Base User", Set.of(Role.USER)));
        userRepository.save(user("admin", "Admin User", Set.of(Role.USER, Role.ADMIN)));
        log.info("Seeded the dev accounts user/user and admin/admin");
    }

    private User user(String username, String name, Set<Role> roles) {
        User user = new User();
        user.setUsername(username);
        user.setName(name);
        user.setHashedPassword(passwordEncoder.encode(username));
        user.setRoles(roles);
        return user;
    }
}
