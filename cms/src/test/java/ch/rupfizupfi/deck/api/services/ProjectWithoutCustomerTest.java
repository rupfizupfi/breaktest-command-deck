package ch.rupfizupfi.deck.api.services;

import ch.rupfizupfi.deck.data.Customer;
import ch.rupfizupfi.deck.data.CustomerRepository;
import ch.rupfizupfi.deck.data.Project;
import ch.rupfizupfi.deck.data.ProjectRepository;
import ch.rupfizupfi.deck.data.User;
import ch.rupfizupfi.deck.hilla.crud.OwnerDataHelper;
import com.vaadin.hilla.exception.EndpointValidationException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
 * The project form starts {@code customer} at the model's empty value, an id-less {@link Customer},
 * and sends it unchanged when none was picked. That used to reach JPA as a transient entity and
 * fail with {@code TransientPropertyValueException} (500). A project needs a customer
 * ({@code customer_id not null}), so the save is now refused as a validation error on
 * {@code customer}, which the form shows on the field.
 */
class ProjectWithoutCustomerTest {

    private static final long CUSTOMER_ID = 7L;

    private ProjectRepository projects;
    private CustomerRepository customers;
    private User me;
    private ProjectService service;

    @BeforeEach
    void createService() {
        projects = mock(ProjectRepository.class);
        customers = mock(CustomerRepository.class);
        me = new User();
        me.setId(1L);

        OwnerDataHelper ownerDataHelper = mock(OwnerDataHelper.class);
        when(ownerDataHelper.getAuthenticatedUser()).thenReturn(me);
        when(ownerDataHelper.isVisible(any(), any())).thenReturn(true);

        service = new ProjectService(customers) {
            @Override
            protected ProjectRepository getRepository() {
                return projects;
            }
        };
        ReflectionTestUtils.setField(service, "ownerDataHelper", ownerDataHelper);
    }

    @AfterEach
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void anEmptyCustomerFromTheFormIsAValidationErrorOnTheField() {
        authenticateAs("ROLE_USER");

        assertThatExceptionOfType(EndpointValidationException.class)
                .isThrownBy(() -> service.save(project(me, new Customer())))
                .satisfies(e -> assertThat(e.getValidationErrorData())
                        .singleElement()
                        .satisfies(data -> assertThat(data.getParameterName()).isEqualTo("customer")));
        verify(projects, never()).save(any());
    }

    @Test
    void aMissingCustomerIsAValidationErrorForAnAdminToo() {
        authenticateAs("ROLE_USER", "ROLE_ADMIN");

        assertThatExceptionOfType(EndpointValidationException.class)
                .isThrownBy(() -> service.save(project(null, null)));
        verify(projects, never()).save(any());
    }

    @Test
    void aBatchWithOneProjectMissingItsCustomerSavesNothing() {
        authenticateAs("ROLE_USER", "ROLE_ADMIN");

        assertThatExceptionOfType(EndpointValidationException.class)
                .isThrownBy(() -> service.saveAll(List.of(project(null, customer(CUSTOMER_ID)), project(null, new Customer()))));
        verify(projects, never()).save(any());
        verify(projects, never()).saveAll(any());
    }

    @Test
    void aProjectWithAPickedCustomerSaves() {
        authenticateAs("ROLE_USER");
        when(customers.findById(CUSTOMER_ID)).thenReturn(Optional.of(customer(CUSTOMER_ID)));
        Project project = project(me, customer(CUSTOMER_ID));
        when(projects.save(project)).thenReturn(project);

        assertThat(service.save(project)).isSameAs(project);
        assertThat(project.customer.getId()).isEqualTo(CUSTOMER_ID);
    }

    @Test
    void anAdminsEmptyOwnerBecomesShared() {
        authenticateAs("ROLE_USER", "ROLE_ADMIN");
        when(customers.findById(CUSTOMER_ID)).thenReturn(Optional.of(customer(CUSTOMER_ID)));
        Project project = project(new User(), customer(CUSTOMER_ID));
        when(projects.save(project)).thenReturn(project);

        service.save(project);

        assertThat(project.owner).isNull();
        verify(projects).save(project);
    }

    private static void authenticateAs(String... authorities) {
        SecurityContextHolder.getContext()
                .setAuthentication(new TestingAuthenticationToken("tester", "n/a", authorities));
    }

    private static Project project(User owner, Customer customer) {
        Project project = new Project();
        project.name = "p";
        project.owner = owner;
        project.customer = customer;
        return project;
    }

    private static Customer customer(Long id) {
        Customer customer = new Customer();
        customer.setId(id);
        return customer;
    }
}
