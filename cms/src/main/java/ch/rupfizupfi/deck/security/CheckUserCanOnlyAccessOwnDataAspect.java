package ch.rupfizupfi.deck.security;

import ch.rupfizupfi.deck.data.User;
import ch.rupfizupfi.deck.hilla.crud.CrudRepositoryService;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;
import org.aspectj.lang.annotation.Pointcut;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Single-row ownership check for a custom method whose <em>first</em> argument is the owned entity
 * or its {@code Long} id; it never filters a list. Owned CRUD services use
 * {@link ch.rupfizupfi.deck.hilla.crud.CrudRepositoryServiceForOwnerData} instead. See
 * {@code doc/03-backend/security-and-tenancy.md}.
 */
@Aspect
@Component
public class CheckUserCanOnlyAccessOwnDataAspect {

    private static final Logger log = LoggerFactory.getLogger(CheckUserCanOnlyAccessOwnDataAspect.class);

    @Pointcut("@annotation(ch.rupfizupfi.deck.security.CheckUserCanOnlyAccessOwnData)")
    public void applyToAllAnnotatedMethods() {
    }

    @Pointcut("within(@ch.rupfizupfi.deck.security.CheckUserCanOnlyAccessOwnData *)")
    public void applyToAllMethodsOfAnnotatedClass() {
    }

    @Autowired
    private AuthenticatedUser authenticatedUser;

    @Before("(applyToAllAnnotatedMethods() || applyToAllMethodsOfAnnotatedClass()) && args(value,..)")
    public void checkUserAccess(JoinPoint joinPoint, Object value) {
        log.debug("Checking ownership for method {}", joinPoint.getSignature().getName());

        if (UserUtils.isAdmin()) {
            return;
        }

        if (value instanceof Long id) {
            value = getEntityById(joinPoint, id);
        }

        User user = authenticatedUser.get().orElseThrow(() -> new SecurityException("User not authenticated"));

        if (value instanceof DataWithOwner valueWithOwner) {
            checkOwnership(user, valueWithOwner);
        }
    }

    /** An id resolves only through the target's own repository. */
    private Object getEntityById(@NotNull JoinPoint joinPoint, Long id) {
        Object target = joinPoint.getThis();
        if (target instanceof CrudRepositoryService<?, ?> crudRepositoryService) {
            CrudRepository<?, Long> crudRepository = crudRepositoryService.getCrudRepository();
            return crudRepository.findById(id).orElseThrow(() -> new SecurityException("Data not found"));
        }
        throw new IllegalStateException("@CheckUserCanOnlyAccessOwnData needs a CrudRepositoryService to resolve a Long id, but "
                + (target == null ? "null" : target.getClass().getName()) + " is not one");
    }

    private void checkOwnership(User user, DataWithOwner valueWithOwner) {
        Optional.ofNullable(valueWithOwner.getOwner())
                .ifPresent(owner -> {
                    if (!owner.getId().equals(user.getId())) {
                        throw new SecurityException("User can only access their own data");
                    }
                });
    }
}
