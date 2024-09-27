package ch.rupfizupfi.deck.security;

import ch.rupfizupfi.deck.data.User;
import ch.rupfizupfi.deck.hilla.crud.CrudRepositoryService;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;
import org.aspectj.lang.annotation.Pointcut;
import org.jetbrains.annotations.NotNull;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.repository.CrudRepository;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Aspect
@Component
public class CheckUserCanOnlyAccessOwnDataAspect {

    CheckUserCanOnlyAccessOwnDataAspect() {
        System.out.println("CheckUserCanOnlyAccessOwnDataAspect init");
    }


    @Pointcut("@annotation(ch.rupfizupfi.deck.security.CheckUserCanOnlyAccessOwnData)")
    public void applyToAllAnnotatedMethods() {}

    @Pointcut("within(@ch.rupfizupfi.deck.security.CheckUserCanOnlyAccessOwnData *)")
    public void applyToAllMethodsOfAnnotatedClass() {}

    @Autowired
    private AuthenticatedUser authenticatedUser;

    @Before("(applyToAllAnnotatedMethods() || applyToAllMethodsOfAnnotatedClass()) && args(value,..)")
    public void checkUserAccess(JoinPoint joinPoint, Object value) {
        String methodName = joinPoint.getSignature().getName();
        System.out.println("Calling method: " + methodName);

        if (isAdmin()) {
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

    private boolean isAdmin() {
        return SecurityContextHolder.getContext().getAuthentication().getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN"));
    }

    private Object getEntityById(@NotNull JoinPoint joinPoint, Long id) {
        Object target = joinPoint.getThis();
        if (target instanceof CrudRepositoryService<?, ?> crudRepositoryService) {
            CrudRepository<?, Long> crudRepository = crudRepositoryService.getCrudRepository();
            return crudRepository.findById(id).orElseThrow(() -> new SecurityException("Data not found"));
        }
        return id;
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