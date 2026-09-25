package ch.rupfizupfi.deck.api.services;

import ch.rupfizupfi.deck.data.Sample;
import ch.rupfizupfi.deck.data.SampleRepository;
import ch.rupfizupfi.deck.data.User;
import ch.rupfizupfi.deck.hilla.crud.OwnerDataHelper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Samples reach the owner scoping of {@code CrudRepositoryServiceForOwnerData}: a non-admin lists
 * through the owner Specification and cannot write a row the scoped {@code get} does not resolve.
 * Both list paths call the same {@code findAll(Specification, Pageable)}, so the admin bypass is
 * observed on {@code OwnerDataHelper} instead.
 */
class SampleServiceTest {

    private static final long FOREIGN_ID = 42L;
    private static final Pageable PAGE = PageRequest.of(0, 20);

    private SampleRepository repository;
    private OwnerDataHelper ownerDataHelper;
    private ScopedSampleService service;

    private static class ScopedSampleService extends SampleService {
        private final SampleRepository repository;

        ScopedSampleService(SampleRepository repository) {
            this.repository = repository;
        }

        @Override
        protected SampleRepository getRepository() {
            return repository;
        }
    }

    @BeforeEach
    void createService() {
        repository = mock(SampleRepository.class);

        User authenticatedUser = new User();
        authenticatedUser.setId(1L);
        ownerDataHelper = mock(OwnerDataHelper.class);
        when(ownerDataHelper.getAuthenticatedUser()).thenReturn(authenticatedUser);
        when(ownerDataHelper.addOwnerCriteriaToSpec(any())).thenAnswer(invocation -> invocation.getArgument(0));

        service = new ScopedSampleService(repository);
        ReflectionTestUtils.setField(service, "ownerDataHelper", ownerDataHelper);
    }

    @AfterEach
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void nonAdminListsThroughTheOwnerSpecification() {
        authenticateAs("ROLE_USER");
        when(repository.findAll(any(Specification.class), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));

        assertThat(service.list(PAGE, null)).isEmpty();

        verify(ownerDataHelper).addOwnerCriteriaToSpec(any());
        verify(repository).findAll(any(Specification.class), any(Pageable.class));
        verify(repository, never()).findAll(any(Pageable.class));
    }

    @Test
    void adminListsUnscoped() {
        authenticateAs("ROLE_USER", "ROLE_ADMIN");
        when(repository.findAll(any(Specification.class), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));

        assertThat(service.list(PAGE, null)).isEmpty();

        verify(ownerDataHelper, never()).addOwnerCriteriaToSpec(any());
        verify(repository).findAll(any(Specification.class), any(Pageable.class));
    }

    @Test
    void nonAdminCannotSaveAnIdTheScopedGetDoesNotResolve() {
        authenticateAs("ROLE_USER");
        when(repository.findOne(any(Specification.class))).thenReturn(Optional.empty());

        assertThatExceptionOfType(SecurityException.class).isThrownBy(() -> service.save(sample(FOREIGN_ID, null)));
        verify(repository, never()).save(any());
    }

    @Test
    void nonAdminSavesANewSharedSample() {
        authenticateAs("ROLE_USER");
        Sample shared = sample(null, null);
        when(repository.save(shared)).thenReturn(shared);

        assertThat(service.save(shared)).isSameAs(shared);
        verify(repository).save(shared);
        verify(repository, never()).findOne(any(Specification.class));
    }

    @Test
    void nonAdminCannotDeleteABatchHoldingAnIdTheScopedGetDoesNotResolve() {
        authenticateAs("ROLE_USER");
        when(repository.findOne(any(Specification.class))).thenReturn(Optional.empty());

        assertThatExceptionOfType(SecurityException.class)
                .isThrownBy(() -> service.deleteAll(List.of(FOREIGN_ID)));
        verify(repository, never()).deleteAllById(any());
        verify(repository, never()).deleteById(any());
    }

    @Test
    void adminDeletesABatchUnscoped() {
        authenticateAs("ROLE_USER", "ROLE_ADMIN");

        service.deleteAll(List.of(FOREIGN_ID));

        verify(repository).deleteAllById(List.of(FOREIGN_ID));
        verify(repository, never()).findOne(any(Specification.class));
    }

    private static void authenticateAs(String... authorities) {
        SecurityContextHolder.getContext()
                .setAuthentication(new TestingAuthenticationToken("tester", "n/a", authorities));
    }

    private static Sample sample(Long id, User owner) {
        Sample sample = new Sample();
        sample.setId(id);
        sample.owner = owner;
        return sample;
    }
}
