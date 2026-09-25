package ch.rupfizupfi.deck.hilla.crud;

import ch.rupfizupfi.deck.data.AbstractEntity;
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

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * CRUD scoped to ownership: a non-admin reaches only rows whose owner is themselves or null
 * (null = shared with everyone), on {@code get}, {@code list}, {@code delete}, {@code save} and
 * their bulk forms alike; admins bypass it. See {@code doc/03-backend/security-and-tenancy.md}.
 */
public class CrudRepositoryServiceForOwnerData<T extends DataWithOwner, R extends CrudRepository<T, Long> & JpaSpecificationExecutor<T>> extends CrudRepositoryService<T, R> {
    @Autowired
    private OwnerDataHelper ownerDataHelper;

    @Override
    public Optional<T> get(@NonNull Long id) {
        if (UserUtils.isAdmin()) {
            return super.get(id);
        }
        return this.getRepository().findOne(ownerDataHelper.addOwnerCriteriaToSpec(Specification.allOf((root, query, criteriaBuilder) -> criteriaBuilder.equal(root.get("id"), id))));
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

    /**
     * The payload's owner must be self or null, and an id must resolve through the scoped
     * {@link #get}. The owner is stored as sent — the frontend's {@code OwnerSelector} picks it.
     */
    @Override
    @Nullable
    public T save(@NonNull T value) {
        if (UserUtils.isAdmin()) {
            return super.save(value);
        }

        var owner = value.getOwner();
        if (owner != null && !owner.equals(ownerDataHelper.getAuthenticatedUser())) {
            throw new SecurityException("You do not have permission to save this record");
        }

        Long id = value instanceof AbstractEntity entity ? entity.getId() : null;
        if (id != null && this.get(id).isEmpty()) {
            throw new SecurityException("You do not have permission to save this record");
        }

        return super.save(value);
    }

    @Override
    @NonNull
    public List<T> saveAll(@NonNull Iterable<T> values) {
        List<T> saved = new ArrayList<>();
        values.forEach(value -> saved.add(this.save(value)));
        return saved;
    }

    /**
     * A batch is checked whole and refused whole: no row goes before the last id has resolved
     * through the scoped {@link #get}.
     */
    @Override
    public void deleteAll(@NonNull Iterable<Long> ids) {
        if (!UserUtils.isAdmin()) {
            for (Long id : ids) {
                if (this.get(id).isEmpty()) {
                    throw new SecurityException("You do not have permission to delete this record");
                }
            }
        }
        super.deleteAll(ids);
    }
}
