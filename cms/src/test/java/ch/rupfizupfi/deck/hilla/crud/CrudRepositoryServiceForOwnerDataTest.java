package ch.rupfizupfi.deck.hilla.crud;

import ch.rupfizupfi.deck.data.TestResult;
import ch.rupfizupfi.deck.data.TestResultRepository;
import ch.rupfizupfi.deck.data.User;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The write half of the owner scoping: {@code save} must refuse a foreign owner in the payload and
 * an id the scoped {@code get} does not resolve, both before the repository is touched.
 */
class CrudRepositoryServiceForOwnerDataTest {

    private static final long OWN_ID = 5L;

    private TestResultRepository repository;
    private User authenticatedUser;
    private OwnerScopedService service;

    /** Concrete subject: the base class is abstract only by generics, and the repository is mocked. */
    private static class OwnerScopedService extends CrudRepositoryServiceForOwnerData<TestResult, TestResultRepository> {
        private final TestResultRepository repository;

        OwnerScopedService(TestResultRepository repository) {
            this.repository = repository;
        }

        @Override
        protected TestResultRepository getRepository() {
            return repository;
        }
    }

    @BeforeEach
    void createService() {
        repository = mock(TestResultRepository.class);
        authenticatedUser = user(1L);

        OwnerDataHelper ownerDataHelper = mock(OwnerDataHelper.class);
        when(ownerDataHelper.getAuthenticatedUser()).thenReturn(authenticatedUser);
        when(ownerDataHelper.addOwnerCriteriaToSpec(any())).thenAnswer(invocation -> invocation.getArgument(0));

        service = new OwnerScopedService(repository);
        ReflectionTestUtils.setField(service, "ownerDataHelper", ownerDataHelper);
    }

    @AfterEach
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void nonAdminCannotSaveARowOwnedBySomeoneElse() {
        authenticateAs("ROLE_USER");
        TestResult foreign = testResult(OWN_ID, user(2L));

        assertThatExceptionOfType(SecurityException.class).isThrownBy(() -> service.save(foreign));
        verify(repository, never()).save(any());
    }

    @Test
    void nonAdminCannotSaveAnIdTheScopedGetDoesNotResolve() {
        authenticateAs("ROLE_USER");
        // Null owner passes the payload check; the id is the second gate — a foreign row's id
        // carries no write access even when the payload claims to be shared.
        when(repository.findOne(any(Specification.class))).thenReturn(Optional.empty());

        TestResult unreachable = testResult(OWN_ID, null);

        assertThatExceptionOfType(SecurityException.class).isThrownBy(() -> service.save(unreachable));
        verify(repository, never()).save(any());
    }

    @Test
    void nonAdminSavesTheirOwnRow() {
        authenticateAs("ROLE_USER");
        TestResult own = testResult(OWN_ID, authenticatedUser);
        when(repository.findOne(any(Specification.class))).thenReturn(Optional.of(own));
        when(repository.save(own)).thenReturn(own);

        assertThat(service.save(own)).isSameAs(own);
        verify(repository).save(own);
    }

    @Test
    void nonAdminSavesANewSharedRow() {
        authenticateAs("ROLE_USER");
        TestResult shared = testResult(null, null);
        when(repository.save(shared)).thenReturn(shared);

        assertThat(service.save(shared)).isSameAs(shared);
        verify(repository).save(shared);
        // A new row never needs the scoped lookup.
        verify(repository, never()).findOne(any(Specification.class));
    }

    @Test
    void adminSavesAForeignRow() {
        authenticateAs("ROLE_USER", "ROLE_ADMIN");
        TestResult foreign = testResult(OWN_ID, user(2L));
        when(repository.save(foreign)).thenReturn(foreign);

        assertThat(service.save(foreign)).isSameAs(foreign);
        verify(repository).save(foreign);
    }

    private static void authenticateAs(String... authorities) {
        SecurityContextHolder.getContext()
                .setAuthentication(new TestingAuthenticationToken("tester", "n/a", authorities));
    }

    private static User user(long id) {
        User user = new User();
        user.setId(id);
        return user;
    }

    private static TestResult testResult(Long id, User owner) {
        TestResult testResult = new TestResult();
        testResult.setId(id);
        testResult.owner = owner;
        return testResult;
    }
}
