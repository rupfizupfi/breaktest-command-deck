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

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Set;

/**
 * The first ADMIN of a deployment, which has no seeded accounts: created as {@code admin} from
 * {@code DECK_ADMIN_PASSWORD}, else from the secret file, and only while no ADMIN exists. With
 * neither set it creates nothing and says so — nobody can log in until one is supplied.
 */
@Component
@Profile("docker")
public class InitialAdminBootstrap implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(InitialAdminBootstrap.class);
    static final String USERNAME = "admin";

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final String passwordFromEnvironment;
    private final Path passwordFile;

    public InitialAdminBootstrap(UserRepository userRepository, PasswordEncoder passwordEncoder,
                                 @Value("${DECK_ADMIN_PASSWORD:}") String passwordFromEnvironment,
                                 @Value("${deck.admin.password-file:/run/secrets/admin-password}") Path passwordFile) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.passwordFromEnvironment = passwordFromEnvironment;
        this.passwordFile = passwordFile;
    }

    @Override
    public void run(ApplicationArguments args) {
        warnAboutWellKnownCredentials();
        if (userRepository.existsWithRole(Role.ADMIN)) {
            return;
        }
        Optional<String> password = password();
        if (password.isEmpty()) {
            log.warn("No ADMIN user exists and none was created: set DECK_ADMIN_PASSWORD or provide {}, then restart.", passwordFile);
            return;
        }
        if (userRepository.findByUsername(USERNAME) != null) {
            log.warn("No ADMIN user exists, but the username '{}' is taken by a non-admin; grant ADMIN by hand, nothing was created.", USERNAME);
            return;
        }

        User admin = new User();
        admin.setUsername(USERNAME);
        admin.setName("Admin User");
        admin.setHashedPassword(passwordEncoder.encode(password.get()));
        admin.setRoles(Set.of(Role.USER, Role.ADMIN));
        userRepository.save(admin);
        log.info("Created the initial ADMIN user '{}'", USERNAME);
    }

    private Optional<String> password() {
        if (!passwordFromEnvironment.isBlank()) {
            return Optional.of(passwordFromEnvironment);
        }
        if (!Files.isReadable(passwordFile)) {
            return Optional.empty();
        }
        try {
            return Optional.of(Files.readString(passwordFile).strip()).filter(password -> !password.isEmpty());
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read the admin password file " + passwordFile, e);
        }
    }

    /** A database first seeded by an older {@code data.sql} still holds {@code admin}/{@code admin} and {@code user}/{@code user}. */
    private void warnAboutWellKnownCredentials() {
        for (String username : new String[]{"admin", "user"}) {
            User user = userRepository.findByUsername(username);
            if (user != null && user.getHashedPassword() != null && passwordEncoder.matches(username, user.getHashedPassword())) {
                log.warn("User '{}' still has the well-known password '{}'; change it.", username, username);
            }
        }
    }
}
