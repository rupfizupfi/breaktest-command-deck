package ch.rupfizupfi.deck.api.services;

import ch.rupfizupfi.deck.data.Customer;
import ch.rupfizupfi.deck.data.CustomerRepository;
import ch.rupfizupfi.deck.data.ProjectRepository;
import ch.rupfizupfi.deck.hilla.crud.CrudRepositoryServiceForOwnerData;
import com.vaadin.hilla.BrowserCallable;
import com.vaadin.hilla.exception.EndpointException;
import jakarta.annotation.security.PermitAll;

/** Owner-scoped like projects: a non-admin reads own and shared customers and changes only their own. */
@BrowserCallable
@PermitAll
public class CustomerService extends CrudRepositoryServiceForOwnerData<Customer, CustomerRepository> {
    static final String REFERENCED_BY_SHARED_PROJECT =
            "owner: a shared project references this customer, so it must stay shared (owner: all)";

    private final ProjectRepository projects;

    CustomerService(ProjectRepository projects) {
        this.projects = projects;
    }

    /** The other side of {@link ProjectService}'s rule: a customer a shared project references cannot get an owner. */
    @Override
    protected void checkInvariants(Customer value) {
        if (value.owner != null && value.getId() != null && projects.existsByCustomer_IdAndOwnerIsNull(value.getId())) {
            throw new EndpointException(REFERENCED_BY_SHARED_PROJECT);
        }
    }
}
