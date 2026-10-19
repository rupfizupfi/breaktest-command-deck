package ch.rupfizupfi.deck.api.services;

import ch.rupfizupfi.deck.data.User;
import ch.rupfizupfi.deck.data.UserRepository;
import com.vaadin.hilla.exception.EndpointException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The browser never sees a password hash, so an edit without a new password must not erase the stored one. */
class UserServiceTest {

    private static final String STORED_HASH = "$2a$10$stored";

    private UserRepository repository;
    private UserService service;

    private static class TestableUserService extends UserService {
        private final UserRepository repository;

        TestableUserService(UserRepository repository) {
            this.repository = repository;
        }

        @Override
        protected UserRepository getRepository() {
            return repository;
        }
    }

    @BeforeEach
    void createService() {
        repository = mock(UserRepository.class);
        when(repository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));
        User stored = new User();
        stored.setId(1L);
        stored.setHashedPassword(STORED_HASH);
        when(repository.findById(1L)).thenReturn(Optional.of(stored));
        service = new TestableUserService(repository);
    }

    @Test
    void anEditWithoutANewPasswordKeepsTheStoredHash() {
        User edit = new User();
        edit.setId(1L);
        edit.setName("Renamed");

        assertThat(service.save(edit).getHashedPassword()).isEqualTo(STORED_HASH);
    }

    @Test
    void aBatchEditKeepsTheStoredHashToo() {
        User edit = new User();
        edit.setId(1L);

        assertThat(service.saveAll(List.of(edit))).extracting(User::getHashedPassword).containsExactly(STORED_HASH);
    }

    @Test
    void aNewPasswordReplacesTheHash() {
        User edit = new User();
        edit.setId(1L);
        edit.setNewPassword("s3cret");

        String hash = service.save(edit).getHashedPassword();

        assertThat(hash).isNotEqualTo(STORED_HASH);
        assertThat(new BCryptPasswordEncoder().matches("s3cret", hash)).isTrue();
    }

    @Test
    void aNewUserWithoutAPasswordIsRefused() {
        User created = new User();
        created.setUsername("nopass");

        assertThatExceptionOfType(EndpointException.class).isThrownBy(() -> service.save(created));
        verify(repository, never()).save(any());
    }
}
