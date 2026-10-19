package ch.rupfizupfi.deck.api.services;

import ch.rupfizupfi.deck.data.Customer;
import ch.rupfizupfi.deck.data.CustomerRepository;
import ch.rupfizupfi.deck.data.Project;
import ch.rupfizupfi.deck.data.ProjectRepository;
import ch.rupfizupfi.deck.data.User;
import ch.rupfizupfi.deck.hilla.crud.OwnerAccessDeniedException;
import ch.rupfizupfi.deck.hilla.crud.OwnerDataHelper;
import com.vaadin.hilla.exception.EndpointException;
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
 * Customers are owner-scoped like projects, and a shared project may only reference a shared
 * customer: {@code ProjectService} checks it on a project's save, {@code CustomerService} on a
 * customer's (so an owner cannot be put on a customer a shared project uses). Both for admins too.
 */
class CustomerOwnershipTest {

    private static final long CUSTOMER_ID = 7L;
    private static final long PROJECT_ID = 9L;
    private static final Pageable PAGE = PageRequest.of(0, 20);

    private CustomerRepository customers;
    private ProjectRepository projects;
    private OwnerDataHelper ownerDataHelper;
    private User me;
    private CustomerService customerService;
    private ProjectService projectService;

    @BeforeEach
    void createServices() {
        customers = mock(CustomerRepository.class);
        projects = mock(ProjectRepository.class);
        me = user(1L);

        ownerDataHelper = mock(OwnerDataHelper.class);
        when(ownerDataHelper.getAuthenticatedUser()).thenReturn(me);
        when(ownerDataHelper.addOwnerCriteriaToSpec(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(ownerDataHelper.isVisible(any(), any())).thenReturn(true);

        customerService = new CustomerService(projects) {
            @Override
            protected CustomerRepository getRepository() {
                return customers;
            }
        };
        ReflectionTestUtils.setField(customerService, "ownerDataHelper", ownerDataHelper);

        projectService = new ProjectService(customers) {
            @Override
            protected ProjectRepository getRepository() {
                return projects;
            }
        };
        ReflectionTestUtils.setField(projectService, "ownerDataHelper", ownerDataHelper);
    }

    @AfterEach
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void nonAdminListsCustomersThroughTheOwnerSpecification() {
        authenticateAs("ROLE_USER");
        when(customers.findAll(any(Specification.class), any(Pageable.class))).thenReturn(new PageImpl<>(List.of()));

        assertThat(customerService.list(PAGE, null)).isEmpty();

        verify(ownerDataHelper).addOwnerCriteriaToSpec(any());
    }

    @Test
    void nonAdminCannotEditASharedCustomer() {
        authenticateAs("ROLE_USER");
        when(customers.findById(CUSTOMER_ID)).thenReturn(Optional.of(customer(CUSTOMER_ID, null)));

        assertThatExceptionOfType(OwnerAccessDeniedException.class)
                .isThrownBy(() -> customerService.save(customer(CUSTOMER_ID, null)));
        verify(customers, never()).save(any());
    }

    @Test
    void nonAdminCannotEditAnotherUsersCustomer() {
        authenticateAs("ROLE_USER");
        when(customers.findById(CUSTOMER_ID)).thenReturn(Optional.of(customer(CUSTOMER_ID, user(2L))));

        assertThatExceptionOfType(OwnerAccessDeniedException.class)
                .isThrownBy(() -> customerService.save(customer(CUSTOMER_ID, me)));
        verify(customers, never()).save(any());
    }

    @Test
    void nonAdminCreatesASharedCustomer() {
        authenticateAs("ROLE_USER");
        Customer shared = customer(null, null);
        when(customers.save(shared)).thenReturn(shared);

        assertThat(customerService.save(shared)).isSameAs(shared);
    }

    @Test
    void nonAdminEditsTheirOwnCustomer() {
        authenticateAs("ROLE_USER");
        when(customers.findById(CUSTOMER_ID)).thenReturn(Optional.of(customer(CUSTOMER_ID, user(1L))));
        Customer own = customer(CUSTOMER_ID, me);
        when(customers.save(own)).thenReturn(own);

        assertThat(customerService.save(own)).isSameAs(own);
    }

    @Test
    void aCustomerASharedProjectReferencesCannotGetAnOwnerEvenFromAnAdmin() {
        authenticateAs("ROLE_USER", "ROLE_ADMIN");
        when(projects.existsByCustomer_IdAndOwnerIsNull(CUSTOMER_ID)).thenReturn(true);

        assertThatExceptionOfType(EndpointException.class)
                .isThrownBy(() -> customerService.save(customer(CUSTOMER_ID, user(2L))))
                .withMessageStartingWith("owner:");
        verify(customers, never()).save(any());
    }

    @Test
    void aSharedProjectCannotReferenceAnOwnedCustomer() {
        authenticateAs("ROLE_USER");
        when(customers.findById(CUSTOMER_ID)).thenReturn(Optional.of(customer(CUSTOMER_ID, me)));

        assertThatExceptionOfType(EndpointException.class)
                .isThrownBy(() -> projectService.save(project(null, customer(CUSTOMER_ID, null))))
                .withMessage(ProjectService.SHARED_PROJECT_NEEDS_SHARED_CUSTOMER)
                .withMessageStartingWith("customer:");
        verify(projects, never()).save(any());
    }

    @Test
    void anAdminIsHeldToTheSharedProjectRuleToo() {
        authenticateAs("ROLE_USER", "ROLE_ADMIN");
        when(customers.findById(CUSTOMER_ID)).thenReturn(Optional.of(customer(CUSTOMER_ID, user(2L))));

        assertThatExceptionOfType(EndpointException.class)
                .isThrownBy(() -> projectService.save(project(null, customer(CUSTOMER_ID, null))));
        verify(projects, never()).save(any());
    }

    @Test
    void aSharedProjectMayReferenceASharedCustomer() {
        authenticateAs("ROLE_USER");
        when(customers.findById(CUSTOMER_ID)).thenReturn(Optional.of(customer(CUSTOMER_ID, null)));
        Project shared = project(null, customer(CUSTOMER_ID, null));
        when(projects.save(shared)).thenReturn(shared);

        assertThat(projectService.save(shared)).isSameAs(shared);
    }

    @Test
    void anOwnedProjectMayReferenceTheOwnersCustomer() {
        authenticateAs("ROLE_USER");
        when(customers.findById(CUSTOMER_ID)).thenReturn(Optional.of(customer(CUSTOMER_ID, me)));
        when(projects.findById(PROJECT_ID)).thenReturn(Optional.of(project(me, customer(CUSTOMER_ID, me))));
        Project own = project(me, customer(CUSTOMER_ID, me));
        own.setId(PROJECT_ID);
        when(projects.save(own)).thenReturn(own);

        assertThat(projectService.save(own)).isSameAs(own);
    }

    @Test
    void aProjectCannotReferenceACustomerTheCallerCannotSee() {
        authenticateAs("ROLE_USER");
        when(ownerDataHelper.isVisible(Customer.class, CUSTOMER_ID)).thenReturn(false);

        assertThatExceptionOfType(OwnerAccessDeniedException.class)
                .isThrownBy(() -> projectService.save(project(me, customer(CUSTOMER_ID, null))))
                .withMessageContaining("customer");
        verify(projects, never()).save(any());
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

    private static Customer customer(Long id, User owner) {
        Customer customer = new Customer();
        customer.setId(id);
        customer.owner = owner;
        return customer;
    }

    private static Project project(User owner, Customer customer) {
        Project project = new Project();
        project.owner = owner;
        project.customer = customer;
        project.name = "p";
        return project;
    }
}
