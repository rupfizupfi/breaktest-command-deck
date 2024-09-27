package ch.rupfizupfi.deck.hilla.crud;

import ch.rupfizupfi.deck.data.User;
import ch.rupfizupfi.deck.security.AuthenticatedUser;
import ch.rupfizupfi.deck.security.DataWithOwner;
import ch.rupfizupfi.deck.security.UserUtils;
import com.vaadin.hilla.Nonnull;
import com.vaadin.hilla.Nullable;
import com.vaadin.hilla.crud.filter.AndFilter;
import com.vaadin.hilla.crud.filter.Filter;
import com.vaadin.hilla.crud.filter.OrFilter;
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
    @Nonnull public List<@Nonnull T> list(Pageable pageable, @Nullable Filter filter) {
        if (UserUtils.isAdmin()) {
            return super.list(pageable, filter);
        }

        var currentOwnerFilter = new PropertyStringFilter();
        currentOwnerFilter.setFilterValue(this.getAuthenticatedUser().getId().toString());
        currentOwnerFilter.setPropertyId("owner.id");
        currentOwnerFilter.setMatcher(PropertyStringFilter.Matcher.EQUALS);
        var emptyOwnerFilter = new PropertyStringFilter();
        emptyOwnerFilter.setFilterValue("1");
        emptyOwnerFilter.setPropertyId("owner.id");
        emptyOwnerFilter.setMatcher(PropertyStringFilter.Matcher.LESS_THAN);
        var ownerFilter = new OrFilter();
        ownerFilter.setChildren(List.of(currentOwnerFilter, emptyOwnerFilter));

        if(filter == null){
            filter = ownerFilter;
        }
        else {
            var rootFilter = new AndFilter();
            rootFilter.setChildren(List.of(ownerFilter, filter));
            filter = rootFilter;
        }

        return super.list(pageable, filter);
    }
}