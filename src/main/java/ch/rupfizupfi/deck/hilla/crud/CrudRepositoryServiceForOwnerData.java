package ch.rupfizupfi.deck.hilla.crud;

import ch.rupfizupfi.deck.data.User;
import ch.rupfizupfi.deck.security.AuthenticatedUser;
import ch.rupfizupfi.deck.security.DataWithOwner;
import ch.rupfizupfi.deck.security.UserUtils;
import com.vaadin.hilla.Nullable;
import com.vaadin.hilla.crud.filter.AndFilter;
import com.vaadin.hilla.crud.filter.Filter;
import com.vaadin.hilla.crud.filter.PropertyStringFilter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Pageable;
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
        return this.getRepository().findOne((root, query, criteriaBuilder) -> criteriaBuilder.and(criteriaBuilder.equal(root.get("id"), id), criteriaBuilder.or(criteriaBuilder.equal(root.get("ownerId"), this.getAuthenticatedUser()), criteriaBuilder.isNull(root.get("ownerId")))));
    }

    protected User getAuthenticatedUser() {
        return this.authenticatedUser.get().orElseThrow(() -> new SecurityException("User not authenticated"));
    }

    @Override
    public List<T> list(Pageable pageable, @Nullable Filter filter) {
        if (UserUtils.isAdmin()) {
            return super.list(pageable, filter);
        }

        var onwerFilter = new PropertyStringFilter();
        onwerFilter.setFilterValue(this.getAuthenticatedUser().getId().toString());
        onwerFilter.setPropertyId("ownerId");
        onwerFilter.setMatcher(PropertyStringFilter.Matcher.EQUALS);

        var rootFilter = new AndFilter();
        rootFilter.setChildren(List.of(filter, onwerFilter));

        return super.list(pageable, filter);
    }
}