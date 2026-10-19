package ch.rupfizupfi.deck.hilla.crud;

import ch.rupfizupfi.deck.data.User;
import ch.rupfizupfi.deck.security.AuthenticatedUser;
import ch.rupfizupfi.deck.security.DataWithOwner;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;

import java.util.Objects;

/**
 * The one owner rule for non-admins. <b>Read</b>: a row whose owner is the caller or null (null =
 * shared with everyone) — {@link #buildOwnerQuery} in a query, {@link #isReadableBy} on a loaded
 * row. <b>Write</b> (update, delete, change of owner): only a row whose <em>stored</em> owner is
 * the caller — {@link #isOwnedBy}; a shared row is read-only. A new row may name the caller or
 * null as owner — {@link #isAssignableOwner}. Admins bypass all of it at the call sites.
 */
@Service
public class OwnerDataHelper {

    @Autowired
    private AuthenticatedUser authenticatedUser;

    @PersistenceContext
    private EntityManager entityManager;

    public User getAuthenticatedUser() {
        return authenticatedUser.get().orElseThrow(() -> new SecurityException("User not authenticated"));
    }

    public <T> Specification<T> addOwnerCriteriaToSpec(Specification<T> spec) {
        return spec.and((root, query, criteriaBuilder) -> buildOwnerQuery(root, criteriaBuilder));
    }

    /** Whether the row of {@code type} with {@code id} passes {@link #buildOwnerQuery} for the caller. */
    public boolean isVisible(Class<?> type, Long id) {
        CriteriaBuilder criteriaBuilder = entityManager.getCriteriaBuilder();
        CriteriaQuery<Long> query = criteriaBuilder.createQuery(Long.class);
        Root<?> root = query.from(type);
        query.select(criteriaBuilder.count(root))
                .where(criteriaBuilder.equal(root.get("id"), id), buildOwnerQuery(root, criteriaBuilder));
        return entityManager.createQuery(query).getSingleResult() > 0;
    }

    public <T> Predicate buildOwnerQuery(Root<T> root, CriteriaBuilder criteriaBuilder) {
        return criteriaBuilder.or(
                criteriaBuilder.equal(root.get("owner"), getAuthenticatedUser()),
                criteriaBuilder.isNull(root.get("owner"))
        );
    }

    /** Write rule: the row's stored owner is {@code caller}. A shared row (owner null) is owned by nobody. */
    public static boolean isOwnedBy(@Nullable DataWithOwner row, User caller) {
        return row != null && row.getOwner() != null && Objects.equals(row.getOwner().getId(), caller.getId());
    }

    /** Read rule on a loaded row: shared, or owned by {@code caller}; {@link #buildOwnerQuery} is its query form. */
    public static boolean isReadableBy(DataWithOwner row, User caller) {
        return row.getOwner() == null || isOwnedBy(row, caller);
    }

    /** The owner a non-admin may give a row: themselves, or null to share it. */
    public static boolean isAssignableOwner(@Nullable User owner, User caller) {
        return owner == null || Objects.equals(owner.getId(), caller.getId());
    }
}
