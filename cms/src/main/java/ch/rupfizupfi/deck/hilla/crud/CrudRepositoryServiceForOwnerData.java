package ch.rupfizupfi.deck.hilla.crud;

import ch.rupfizupfi.deck.data.AbstractEntity;
import ch.rupfizupfi.deck.data.User;
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
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToOne;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * CRUD scoped to ownership, for non-admins; admins bypass it. See
 * {@code doc/dev/backend/security-and-tenancy.md}.
 * <ul>
 *   <li><b>Read</b> ({@code get}, {@code list}, {@code count}, {@code exists}): rows whose owner is
 *   the caller or null (null = shared with everyone).</li>
 *   <li><b>Write</b> ({@code save} of an existing row, {@code delete}, and their bulk forms): only
 *   rows whose <em>stored</em> owner is the caller. A shared row is read-only, so it can be neither
 *   changed, deleted nor claimed.</li>
 *   <li><b>Create</b>: a new row may name the caller or null as owner — anyone may share a row.</li>
 * </ul>
 * The rules themselves live in {@link OwnerDataHelper}.
 */
public class CrudRepositoryServiceForOwnerData<T extends DataWithOwner, R extends CrudRepository<T, Long> & JpaSpecificationExecutor<T>> extends CrudRepositoryService<T, R> {
    static final String NOT_PERMITTED = "You do not have permission to change this record";
    static final String SHARED_IS_READ_ONLY = "This record is shared with all users (owner: all) and is read-only; only an administrator can change or delete it";
    static final String OWNER_NOT_ASSIGNABLE = "A record can only be saved with yourself or all users as owner";

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
        return this.getRepository().findAll(ownerDataHelper.addOwnerCriteriaToSpec(spec), PageCap.clamp(pageable)).getContent();
    }

    @Override
    public long count(@Nullable Filter filter) {
        if (UserUtils.isAdmin()) {
            return super.count(filter);
        }
        return this.getRepository().count(ownerDataHelper.addOwnerCriteriaToSpec(this.toSpec(filter)));
    }

    @Override
    public boolean exists(@NonNull Long id) {
        return this.get(id).isPresent();
    }

    @Override
    public void delete(@NonNull Long id) {
        if (!UserUtils.isAdmin()) {
            requireWritable(id, ownerDataHelper.getAuthenticatedUser());
        }
        super.delete(id);
    }

    /**
     * The payload's owner must be self or null; an id must name a row whose stored owner is the
     * caller (the payload's owner says nothing about the stored one); and every owned row it
     * references (a sample's project, a result's sample and parameter) must be readable. The owner
     * is stored as sent — the frontend's {@code OwnerSelector} picks it.
     */
    @Override
    @Nullable
    public T save(@NonNull T value) {
        if (UserUtils.isAdmin()) {
            checkInvariants(value);
            return super.save(value);
        }

        User caller = ownerDataHelper.getAuthenticatedUser();
        if (!OwnerDataHelper.isAssignableOwner(value.getOwner(), caller)) {
            throw new OwnerAccessDeniedException(OWNER_NOT_ASSIGNABLE);
        }

        Long id = value instanceof AbstractEntity entity ? entity.getId() : null;
        if (id != null) {
            requireWritable(id, caller);
        }

        checkOwnedReferences(value);
        checkInvariants(value);
        return super.save(value);
    }

    /**
     * Rules a row must satisfy whoever saves it, admins included; runs after the owner checks, so a
     * refusal here never describes a row the caller could not read. Throw an
     * {@link com.vaadin.hilla.exception.EndpointException} naming the field.
     */
    protected void checkInvariants(T value) {
    }

    /**
     * Loads the stored row unscoped — the read predicate admits shared rows, the write rule must not
     * — and refuses it unless {@link OwnerDataHelper#isOwnedBy} holds. A missing row and another
     * owner's row get the same message, so the refusal discloses nothing.
     */
    private void requireWritable(Long id, User caller) {
        T stored = this.getRepository().findById(id)
                .orElseThrow(() -> new OwnerAccessDeniedException(NOT_PERMITTED));
        if (OwnerDataHelper.isOwnedBy(stored, caller)) {
            return;
        }
        throw new OwnerAccessDeniedException(stored.getOwner() == null ? SHARED_IS_READ_ONLY : NOT_PERMITTED);
    }

    /** Every {@code @ManyToOne}/{@code @OneToOne} field whose type is a {@link DataWithOwner} must name a row the caller sees. */
    private void checkOwnedReferences(T value) {
        for (Class<?> type = value.getClass(); type != null && type != Object.class; type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                if (!(field.isAnnotationPresent(ManyToOne.class) || field.isAnnotationPresent(OneToOne.class))
                        || !DataWithOwner.class.isAssignableFrom(field.getType())) {
                    continue;
                }
                Object target = readField(field, value);
                if (target instanceof AbstractEntity reference && reference.getId() != null
                        && !ownerDataHelper.isVisible(field.getType(), reference.getId())) {
                    throw new OwnerAccessDeniedException("You do not have permission to reference this " + field.getName());
                }
            }
        }
    }

    private static Object readField(Field field, Object owner) {
        try {
            field.setAccessible(true);
            return field.get(owner);
        } catch (IllegalAccessException | RuntimeException e) {
            // An unreadable reference cannot be proven visible.
            throw new SecurityException("Cannot check the reference " + field.getName(), e);
        }
    }

    @Override
    @NonNull
    public List<T> saveAll(@NonNull Iterable<T> values) {
        // Refuse a batch with a missing required reference before its first row is written.
        values.forEach(ToOneReferences::normalize);
        List<T> saved = new ArrayList<>();
        values.forEach(value -> saved.add(this.save(value)));
        return saved;
    }

    /**
     * A batch is checked whole and refused whole: no row goes before the last id has passed the
     * write rule.
     */
    @Override
    public void deleteAll(@NonNull Iterable<Long> ids) {
        if (!UserUtils.isAdmin()) {
            User caller = ownerDataHelper.getAuthenticatedUser();
            for (Long id : ids) {
                requireWritable(id, caller);
            }
        }
        super.deleteAll(ids);
    }
}
