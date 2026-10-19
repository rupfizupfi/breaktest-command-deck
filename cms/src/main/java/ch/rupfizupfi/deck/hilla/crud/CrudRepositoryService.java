package ch.rupfizupfi.deck.hilla.crud;

import com.vaadin.hilla.crud.filter.AndFilter;
import com.vaadin.hilla.crud.filter.Filter;
import com.vaadin.hilla.crud.filter.OrFilter;
import com.vaadin.hilla.crud.filter.PropertyStringFilter;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.repository.CrudRepository;
import org.springframework.lang.Nullable;

import java.util.List;
import java.util.Set;

public class CrudRepositoryService<T, R extends CrudRepository<T, Long> & JpaSpecificationExecutor<T>> extends com.vaadin.hilla.crud.CrudRepositoryService<T, Long, R> {
    /** The only {@code owner} properties a grid filters on; anything else past an owner is another user's row. */
    private static final Set<String> FILTERABLE_OWNER_PROPERTIES = Set.of("username", "name");

    public CrudRepository<T, Long> getCrudRepository() {
        return getRepository();
    }

    /**
     * An id-less reference from the form becomes null, and a required one that is then missing is a
     * validation error on its field rather than a 500 — see {@link ToOneReferences}.
     */
    @Override
    @Nullable
    public T save(T value) {
        ToOneReferences.normalize(value);
        return super.save(value);
    }

    /** Every row is normalised before any is written, so a refusal leaves the batch unsaved. */
    @Override
    public List<T> saveAll(Iterable<T> values) {
        values.forEach(ToOneReferences::normalize);
        return super.saveAll(values);
    }

    /** The client picks the page size; {@link PageCap} bounds it. */
    @Override
    public List<T> list(Pageable pageable, @Nullable Filter filter) {
        return super.list(PageCap.clamp(pageable), filter);
    }

    /**
     * A filter is client input that Hilla turns into a join, so a property path could probe what the
     * row's JSON never shows: a password hash, or an owner's other columns. Refused before the query
     * is built, for admins too.
     */
    @Override
    protected Specification<T> toSpec(@Nullable Filter filter) {
        rejectProtectedProperties(filter);
        return super.toSpec(filter);
    }

    static void rejectProtectedProperties(@Nullable Filter filter) {
        switch (filter) {
            case null -> {
            }
            case AndFilter and -> and.getChildren().forEach(CrudRepositoryService::rejectProtectedProperties);
            case OrFilter or -> or.getChildren().forEach(CrudRepositoryService::rejectProtectedProperties);
            case PropertyStringFilter property -> rejectProtectedPath(property.getPropertyId());
            default -> {
            }
        }
    }

    private static void rejectProtectedPath(@Nullable String propertyId) {
        if (propertyId == null) {
            return;
        }
        String[] segments = propertyId.split("\\.");
        for (int i = 0; i < segments.length; i++) {
            if (segments[i].equals("hashedPassword") || segments[i].equals("newPassword")) {
                throw new SecurityException("Cannot filter on " + propertyId);
            }
            if (segments[i].equals("owner") && i < segments.length - 1
                    && (i != segments.length - 2 || !FILTERABLE_OWNER_PROPERTIES.contains(segments[i + 1]))) {
                throw new SecurityException("Cannot filter on " + propertyId);
            }
        }
    }
}
