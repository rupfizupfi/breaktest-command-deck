package ch.rupfizupfi.deck.hilla.crud;

import ch.rupfizupfi.deck.security.DataWithOwner;
import ch.rupfizupfi.deck.security.UserUtils;
import com.vaadin.hilla.Nonnull;
import com.vaadin.hilla.Nullable;
import com.vaadin.hilla.crud.filter.Filter;
import org.jetbrains.annotations.NotNull;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.repository.CrudRepository;

import java.util.List;
import java.util.Optional;

public class CrudRepositoryServiceForOwnerData<T extends DataWithOwner, R extends CrudRepository<T, Long> & JpaSpecificationExecutor<T>> extends CrudRepositoryService<T, R> {
    @Autowired
    private OwnerDataHelper ownerDataHelper;

    @NotNull
    @Override
    public Optional<T> get(@NotNull Long id) {
        if (UserUtils.isAdmin()) {
            return super.get(id);
        }
        return this.getRepository().findOne(ownerDataHelper.addOwnerCriteriaToSpec(Specification.where((root, query, criteriaBuilder) -> criteriaBuilder.equal(root.get("id"), id))));
    }

    @Override
    @Nonnull
    public List<@Nonnull T> list(@NotNull Pageable pageable, @Nullable Filter filter) {
        if (UserUtils.isAdmin()) {
            return super.list(pageable, filter);
        }

        Specification<T> spec = this.toSpec(filter);
        return this.getRepository().findAll(ownerDataHelper.addOwnerCriteriaToSpec(spec), pageable).getContent();
    }

    @Override
    public void delete(@NotNull Long id) {
        Optional<T> entity = this.get(id);
        if (entity.isPresent()) {
            super.delete(id);
        } else {
            throw new SecurityException("You do not have permission to delete this record");
        }
    }
}