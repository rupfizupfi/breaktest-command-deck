package ch.rupfizupfi.deck.api.services;

import ch.rupfizupfi.deck.data.CustomerRepository;
import ch.rupfizupfi.deck.data.Project;
import ch.rupfizupfi.deck.data.ProjectRepository;
import ch.rupfizupfi.deck.hilla.crud.CrudRepositoryServiceForOwnerData;
import com.vaadin.hilla.BrowserCallable;
import com.vaadin.hilla.exception.EndpointException;
import jakarta.annotation.security.PermitAll;

@BrowserCallable
@PermitAll
public class ProjectService extends CrudRepositoryServiceForOwnerData<Project, ProjectRepository> {
    static final String SHARED_PROJECT_NEEDS_SHARED_CUSTOMER =
            "customer: a shared project (owner: all) can only reference a shared customer (owner: all)";

    private final CustomerRepository customers;

    ProjectService(CustomerRepository customers) {
        this.customers = customers;
    }

    /**
     * A shared project is visible to every user, so its customer must be too: the stored customer,
     * not the payload's copy of it, must be shared. Holds for admins as well.
     */
    @Override
    protected void checkInvariants(Project value) {
        if (value.owner != null || value.customer == null || value.customer.getId() == null) {
            return;
        }
        if (customers.findById(value.customer.getId()).map(stored -> stored.owner != null).orElse(false)) {
            throw new EndpointException(SHARED_PROJECT_NEEDS_SHARED_CUSTOMER);
        }
    }
}
