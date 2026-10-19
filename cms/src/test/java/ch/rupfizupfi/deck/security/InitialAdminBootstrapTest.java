package ch.rupfizupfi.deck.security;

import ch.rupfizupfi.deck.data.Role;
import ch.rupfizupfi.deck.data.User;
import ch.rupfizupfi.deck.data.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** A deployment gets its first ADMIN from the operator's secret, never from a well-known default, and only once. */
class InitialAdminBootstrapTest {

    @TempDir
    Path tempDir;

    private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder();
    private UserRepository repository;

    @BeforeEach
    void createRepository() {
        repository = mock(UserRepository.class);
    }

    @Test
    void neitherSourceSetCreatesNobody() {
        new InitialAdminBootstrap(repository, passwordEncoder, "", tempDir.resolve("absent")).run(null);

        verify(repository, never()).save(any());
    }

    @Test
    void theEnvironmentPasswordCreatesAnAdmin() {
        new InitialAdminBootstrap(repository, passwordEncoder, "from-env", tempDir.resolve("absent")).run(null);

        User admin = savedUser();
        assertThat(admin.getUsername()).isEqualTo("admin");
        assertThat(admin.getRoles()).contains(Role.ADMIN);
        assertThat(passwordEncoder.matches("from-env", admin.getHashedPassword())).isTrue();
    }

    @Test
    void theSecretFileIsReadWhenTheEnvironmentIsBlank() throws IOException {
        Path secret = Files.writeString(tempDir.resolve("admin-password"), "from-file\n");

        new InitialAdminBootstrap(repository, passwordEncoder, " ", secret).run(null);

        assertThat(passwordEncoder.matches("from-file", savedUser().getHashedPassword())).isTrue();
    }

    @Test
    void anExistingAdminIsLeftAlone() {
        when(repository.existsWithRole(Role.ADMIN)).thenReturn(true);

        new InitialAdminBootstrap(repository, passwordEncoder, "from-env", tempDir.resolve("absent")).run(null);

        verify(repository, never()).save(any());
    }

    @Test
    void aTakenUsernameIsNotOverwritten() {
        when(repository.findByUsername("admin")).thenReturn(new User());

        new InitialAdminBootstrap(repository, passwordEncoder, "from-env", tempDir.resolve("absent")).run(null);

        verify(repository, never()).save(any());
    }

    private User savedUser() {
        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(repository).save(captor.capture());
        return captor.getValue();
    }
}
