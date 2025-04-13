package ch.rupfizupfi.deck.hilla.crud;

import ch.rupfizupfi.deck.security.DataWithOwner;
import ch.rupfizupfi.deck.security.UserUtils;
import org.springframework.lang.NonNull;
import org.springframework.lang.Nullable;
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
    private OwnerDataHelper ownerDataHelper;

    @Override
    public Optional<T> get(@NonNull Long id) {
        if (UserUtils.isAdmin()) {
            return super.get(id);
        }
        return this.getRepository().findOne(ownerDataHelper.addOwnerCriteriaToSpec(Specification.where((root, query, criteriaBuilder) -> criteriaBuilder.equal(root.get("id"), id))));
    }

    @Override
    @NonNull
    public List<T> list(@NonNull Pageable pageable, @Nullable Filter filter) {
        if (UserUtils.isAdmin()) {
            return super.list(pageable, filter);
        }

        Specification<T> spec = this.toSpec(filter);
        return this.getRepository().findAll(ownerDataHelper.addOwnerCriteriaToSpec(spec), pageable).getContent();
    }

    @Override
    public void delete(@NonNull Long id) {
        Optional<T> entity = this.get(id);
        if (entity.isPresent()) {
            super.delete(id);
        } else {
            throw new SecurityException("You do not have permission to delete this record");
        }
    }
}