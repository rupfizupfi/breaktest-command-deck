package ch.rupfizupfi.deck.hilla.crud;

import ch.rupfizupfi.deck.data.User;
import ch.rupfizupfi.deck.security.AuthenticatedUser;
import ch.rupfizupfi.deck.security.DataWithOwner;
import ch.rupfizupfi.deck.security.UserUtils;
import com.vaadin.hilla.Nonnull;
import com.vaadin.hilla.Nullable;
import com.vaadin.hilla.crud.filter.Filter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.repository.CrudRepository;

import java.util.List;
import java.util.Optional;

public class CrudRepositoryServiceForOwnerData<T extends DataWithOwner, R extends CrudRepository<T, Long> & JpaSpecificationExecutor<T>> extends CrudRepositoryService<T, R> {
    @Autowired
    private AuthenticatedUser authenticatedUser;

    @Override
    public Optional<T> get(Long id) {
        if (UserUtils.isAdmin()) {
            return super.get(id);
        }
        return this.getRepository().findOne(addOwnerCriteriaToSpec(Specification.where(null)));
    }

    protected User getAuthenticatedUser() {
        return this.authenticatedUser.get().orElseThrow(() -> new SecurityException("User not authenticated"));
    }

    @Override
    @Nonnull
    public List<@Nonnull T> list(Pageable pageable, @Nullable Filter filter) {
        if (UserUtils.isAdmin()) {
            return super.list(pageable, filter);
        }

        Specification<T> spec = this.toSpec(filter);
        return this.getRepository().findAll(addOwnerCriteriaToSpec(spec), pageable).getContent();
    }

    protected Specification<T> addOwnerCriteriaToSpec(Specification<T> spec) {
        return spec.and((root, query, criteriaBuilder) -> criteriaBuilder.or(criteriaBuilder.equal(root.get("owner"), this.getAuthenticatedUser()), criteriaBuilder.isNull(root.get("owner"))));
    }
}