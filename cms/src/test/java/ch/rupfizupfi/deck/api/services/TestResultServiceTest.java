package ch.rupfizupfi.deck.api.services;

import ch.rupfizupfi.deck.data.TestResultRepository;
import ch.rupfizupfi.deck.data.User;
import ch.rupfizupfi.deck.filesystem.CSVStoreService;
import ch.rupfizupfi.deck.hilla.crud.OwnerDataHelper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** A result the owner scoping does not resolve yields no force data and no CSV listing. */
class TestResultServiceTest {

    private static final long FOREIGN_ID = 42L;

    private CSVStoreService csvStoreService;
    private ScopedTestResultService service;

    private static class ScopedTestResultService extends TestResultService {
        private final TestResultRepository repository;

        ScopedTestResultService(TestResultRepository repository) {
            this.repository = repository;
        }

        @Override
        protected TestResultRepository getRepository() {
            return repository;
        }
    }

    @BeforeEach
    void createService() {
        TestResultRepository repository = mock(TestResultRepository.class);
        when(repository.findOne(any(Specification.class))).thenReturn(Optional.empty());

        User authenticatedUser = new User();
        authenticatedUser.setId(1L);
        OwnerDataHelper ownerDataHelper = mock(OwnerDataHelper.class);
        when(ownerDataHelper.getAuthenticatedUser()).thenReturn(authenticatedUser);
        when(ownerDataHelper.addOwnerCriteriaToSpec(any())).thenAnswer(invocation -> invocation.getArgument(0));

        csvStoreService = mock(CSVStoreService.class);

        service = new ScopedTestResultService(repository);
        ReflectionTestUtils.setField(service, "ownerDataHelper", ownerDataHelper);
        ReflectionTestUtils.setField(service, "csvStoreService", csvStoreService);

        SecurityContextHolder.getContext()
                .setAuthentication(new TestingAuthenticationToken("tester", "n/a", "ROLE_USER"));
    }

    @AfterEach
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void readingCsvDataOfAnUnreachableResultIsRefused() {
        assertThatExceptionOfType(SecurityException.class)
                .isThrownBy(() -> service.readCSVData(FOREIGN_ID, "1_force.csv"));
        verifyNoInteractions(csvStoreService);
    }

    @Test
    void listingCsvResultsOfAnUnreachableResultIsRefused() {
        assertThatExceptionOfType(SecurityException.class)
                .isThrownBy(() -> service.listCSVResults(FOREIGN_ID));
        verifyNoInteractions(csvStoreService);
    }
}
