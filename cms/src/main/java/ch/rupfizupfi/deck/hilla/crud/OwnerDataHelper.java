package ch.rupfizupfi.deck.hilla.crud;

import ch.rupfizupfi.deck.data.User;
import ch.rupfizupfi.deck.security.AuthenticatedUser;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;

@Service
public class OwnerDataHelper {

    @Autowired
    private AuthenticatedUser authenticatedUser;

    public User getAuthenticatedUser() {
        return authenticatedUser.get().orElseThrow(() -> new SecurityException("User not authenticated"));
    }

    public <T> Specification<T> addOwnerCriteriaToSpec(Specification<T> spec) {
        return spec.and((root, query, criteriaBuilder) -> buildOwnerQuery(root, criteriaBuilder));
    }

    public <T> Predicate buildOwnerQuery(Root<T> root, CriteriaBuilder criteriaBuilder) {
        return criteriaBuilder.or(
                criteriaBuilder.equal(root.get("owner"), getAuthenticatedUser()),
                criteriaBuilder.isNull(root.get("owner"))
        );
    }
}
