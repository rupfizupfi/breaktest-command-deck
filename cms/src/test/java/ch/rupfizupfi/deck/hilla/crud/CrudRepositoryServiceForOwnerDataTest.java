package ch.rupfizupfi.deck.hilla.crud;

import ch.rupfizupfi.deck.data.Sample;
import ch.rupfizupfi.deck.data.TestParameter;
import ch.rupfizupfi.deck.data.TestResult;
import ch.rupfizupfi.deck.data.TestResultRepository;
import ch.rupfizupfi.deck.data.User;
import com.vaadin.hilla.crud.filter.AndFilter;
import com.vaadin.hilla.crud.filter.PropertyStringFilter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The write half of the owner scoping: {@code save} must refuse a foreign owner in the payload, an
 * id whose <em>stored</em> row the caller does not own (a shared row included: shared is read-only
 * for non-admins), and a reference to an owned row the caller cannot see, all before the
 * repository is touched; {@code delete} and {@code deleteAll} hold to the same stored-owner rule. {@code count} and {@code exists} are scoped like
 * {@code list} and {@code get}, and a filter cannot reach a password hash or past an owner's name.
 */
class CrudRepositoryServiceForOwnerDataTest {

    private static final long OWN_ID = 5L;

    private TestResultRepository repository;
    private OwnerDataHelper ownerDataHelper;
    private User authenticatedUser;
    private OwnerScopedService service;

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

        ownerDataHelper = mock(OwnerDataHelper.class);
        when(ownerDataHelper.getAuthenticatedUser()).thenReturn(authenticatedUser);
        when(ownerDataHelper.addOwnerCriteriaToSpec(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(ownerDataHelper.isVisible(any(), any())).thenReturn(true);

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

        assertThatExceptionOfType(OwnerAccessDeniedException.class).isThrownBy(() -> service.save(foreign));
        verify(repository, never()).save(any());
    }

    @Test
    void nonAdminCannotSaveAnIdWithNoStoredRow() {
        authenticateAs("ROLE_USER");
        // A null owner passes the payload check; the unknown id must still be refused.
        when(repository.findById(OWN_ID)).thenReturn(Optional.empty());

        TestResult unreachable = testResult(OWN_ID, null);

        assertThatExceptionOfType(OwnerAccessDeniedException.class).isThrownBy(() -> service.save(unreachable));
        verify(repository, never()).save(any());
    }

    @Test
    void nonAdminSavesTheirOwnRow() {
        authenticateAs("ROLE_USER");
        TestResult own = testResult(OWN_ID, authenticatedUser);
        // A separately loaded User: ownership compares ids, not instances.
        storedRow(OWN_ID, user(1L));
        when(repository.save(own)).thenReturn(own);

        assertThat(service.save(own)).isSameAs(own);
        verify(repository).save(own);
    }

    @Test
    void nonAdminCannotEditASharedRow() {
        authenticateAs("ROLE_USER");
        storedRow(OWN_ID, null);

        assertThatExceptionOfType(OwnerAccessDeniedException.class)
                .isThrownBy(() -> service.save(testResult(OWN_ID, null)))
                .withMessage(CrudRepositoryServiceForOwnerData.SHARED_IS_READ_ONLY);
        verify(repository, never()).save(any());
    }

    @Test
    void nonAdminCannotClaimASharedRow() {
        authenticateAs("ROLE_USER");
        storedRow(OWN_ID, null);

        assertThatExceptionOfType(OwnerAccessDeniedException.class)
                .isThrownBy(() -> service.save(testResult(OWN_ID, authenticatedUser)));
        verify(repository, never()).save(any());
    }

    @Test
    void nonAdminCannotEditAnotherOwnersRowByNamingThemselvesInThePayload() {
        authenticateAs("ROLE_USER");
        storedRow(OWN_ID, user(2L));

        assertThatExceptionOfType(OwnerAccessDeniedException.class)
                .isThrownBy(() -> service.save(testResult(OWN_ID, authenticatedUser)))
                .withMessage(CrudRepositoryServiceForOwnerData.NOT_PERMITTED);
        verify(repository, never()).save(any());
    }

    @Test
    void nonAdminMayShareTheirOwnRow() {
        authenticateAs("ROLE_USER");
        storedRow(OWN_ID, user(1L));
        TestResult nowShared = testResult(OWN_ID, null);
        when(repository.save(nowShared)).thenReturn(nowShared);

        assertThat(service.save(nowShared)).isSameAs(nowShared);
    }

    @Test
    void nonAdminCannotDeleteASharedRow() {
        authenticateAs("ROLE_USER");
        storedRow(OWN_ID, null);

        assertThatExceptionOfType(OwnerAccessDeniedException.class).isThrownBy(() -> service.delete(OWN_ID));
        verify(repository, never()).deleteById(any());
    }

    @Test
    void nonAdminCannotDeleteAnotherOwnersRow() {
        authenticateAs("ROLE_USER");
        storedRow(OWN_ID, user(2L));

        assertThatExceptionOfType(OwnerAccessDeniedException.class).isThrownBy(() -> service.delete(OWN_ID));
        verify(repository, never()).deleteById(any());
    }

    @Test
    void nonAdminDeletesTheirOwnRow() {
        authenticateAs("ROLE_USER");
        storedRow(OWN_ID, user(1L));

        service.delete(OWN_ID);

        verify(repository).deleteById(OWN_ID);
    }

    @Test
    void nonAdminBatchDeleteHoldingASharedRowIsRefusedWhole() {
        authenticateAs("ROLE_USER");
        storedRow(OWN_ID, user(1L));
        storedRow(OWN_ID + 1, null);

        assertThatExceptionOfType(OwnerAccessDeniedException.class)
                .isThrownBy(() -> service.deleteAll(List.of(OWN_ID, OWN_ID + 1)));
        verify(repository, never()).deleteById(any());
        verify(repository, never()).deleteAllById(any());
    }

    @Test
    void adminEditsAndDeletesASharedRow() {
        authenticateAs("ROLE_USER", "ROLE_ADMIN");
        TestResult shared = testResult(OWN_ID, null);
        when(repository.save(shared)).thenReturn(shared);

        assertThat(service.save(shared)).isSameAs(shared);
        service.delete(OWN_ID);

        verify(repository).deleteById(OWN_ID);
        verify(repository, never()).findById(any());
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

    @Test
    void nonAdminCannotSaveARowReferencingAnInvisibleSample() {
        authenticateAs("ROLE_USER");
        TestResult payload = testResult(null, authenticatedUser);
        payload.sample = new Sample();
        payload.sample.setId(30L);
        when(ownerDataHelper.isVisible(Sample.class, 30L)).thenReturn(false);

        assertThatExceptionOfType(OwnerAccessDeniedException.class).isThrownBy(() -> service.save(payload));
        verify(repository, never()).save(any());
    }

    @Test
    void nonAdminSavesARowReferencingVisibleRows() {
        authenticateAs("ROLE_USER");
        TestResult payload = testResult(null, authenticatedUser);
        payload.sample = new Sample();
        payload.sample.setId(30L);
        payload.testParameter = new TestParameter();
        payload.testParameter.setId(40L);
        when(ownerDataHelper.isVisible(Sample.class, 30L)).thenReturn(true);
        when(ownerDataHelper.isVisible(TestParameter.class, 40L)).thenReturn(true);
        when(repository.save(payload)).thenReturn(payload);

        assertThat(service.save(payload)).isSameAs(payload);
    }

    @Test
    void adminSavesAReferenceWithoutTheVisibilityCheck() {
        authenticateAs("ROLE_USER", "ROLE_ADMIN");
        TestResult payload = testResult(null, null);
        payload.sample = new Sample();
        payload.sample.setId(30L);
        when(repository.save(payload)).thenReturn(payload);

        assertThat(service.save(payload)).isSameAs(payload);
        verify(ownerDataHelper, never()).isVisible(any(), any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void nonAdminCountIsOwnerScoped() {
        authenticateAs("ROLE_USER");
        Specification<TestResult> scoped = mock(Specification.class);
        when(ownerDataHelper.addOwnerCriteriaToSpec(any())).thenAnswer(invocation -> scoped);
        when(repository.count(scoped)).thenReturn(3L);

        assertThat(service.count(null)).isEqualTo(3L);
        verify(repository, never()).count();
    }

    @Test
    void nonAdminExistsGoesThroughTheScopedGet() {
        authenticateAs("ROLE_USER");
        when(repository.findOne(any(Specification.class))).thenReturn(Optional.empty());

        assertThat(service.exists(OWN_ID)).isFalse();
        verify(repository, never()).existsById(eq(OWN_ID));
    }

    @Test
    void aFilterOnAPasswordHashIsRefused() {
        authenticateAs("ROLE_USER", "ROLE_ADMIN");
        AndFilter filter = new AndFilter();
        filter.setChildren(List.of(filter("owner.hashedPassword")));

        assertThatExceptionOfType(SecurityException.class).isThrownBy(() -> service.list(Pageable.unpaged(), filter));
        assertThatExceptionOfType(SecurityException.class).isThrownBy(() -> service.count(filter));
    }

    @Test
    void aFilterPastAnOwnersNameIsRefused() {
        authenticateAs("ROLE_USER");

        assertThatExceptionOfType(SecurityException.class).isThrownBy(() -> service.count(filter("sample.owner.roles")));
        assertThatExceptionOfType(SecurityException.class).isThrownBy(() -> service.count(filter("owner.username.x")));
    }

    @Test
    void theOwnerGridColumnsFiltersStillPass() {
        assertThatNoException().isThrownBy(() -> CrudRepositoryService.rejectProtectedProperties(filter("owner.username")));
        assertThatNoException().isThrownBy(() -> CrudRepositoryService.rejectProtectedProperties(filter("owner.name")));
        assertThatNoException().isThrownBy(() -> CrudRepositoryService.rejectProtectedProperties(filter("sample.name")));
    }

    private static PropertyStringFilter filter(String propertyId) {
        PropertyStringFilter filter = new PropertyStringFilter();
        filter.setPropertyId(propertyId);
        filter.setMatcher(PropertyStringFilter.Matcher.CONTAINS);
        filter.setFilterValue("x");
        return filter;
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

    private void storedRow(long id, User owner) {
        when(repository.findById(id)).thenReturn(Optional.of(testResult(id, owner)));
    }

    private static TestResult testResult(Long id, User owner) {
        TestResult testResult = new TestResult();
        testResult.setId(id);
        testResult.owner = owner;
        // A test result needs both (optional = false); a save without them is a validation error.
        testResult.sample = new Sample();
        testResult.sample.setId(30L);
        testResult.testParameter = new TestParameter();
        testResult.testParameter.setId(40L);
        return testResult;
    }
}
