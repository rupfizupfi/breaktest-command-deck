package ch.rupfizupfi.deck.security;

import ch.rupfizupfi.deck.data.Project;
import ch.rupfizupfi.deck.data.User;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.Signature;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A Long id on a target that is no {@code CrudRepositoryService} is unresolvable, so the check fails
 * loudly. The aspect applies the read rule from {@code OwnerDataHelper}: own and shared rows pass.
 */
class CheckUserCanOnlyAccessOwnDataAspectTest {

    private CheckUserCanOnlyAccessOwnDataAspect aspect;
    private AuthenticatedUser authenticatedUser;

    @BeforeEach
    void createAspect() {
        aspect = new CheckUserCanOnlyAccessOwnDataAspect();
        authenticatedUser = mock(AuthenticatedUser.class);
        ReflectionTestUtils.setField(aspect, "authenticatedUser", authenticatedUser);
        SecurityContextHolder.getContext()
                .setAuthentication(new TestingAuthenticationToken("tester", "n/a", "ROLE_USER"));
    }

    @AfterEach
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void anIdOnANonCrudTargetIsRefused() {
        Signature signature = mock(Signature.class);
        when(signature.getName()).thenReturn("readSomething");
        JoinPoint joinPoint = mock(JoinPoint.class);
        when(joinPoint.getSignature()).thenReturn(signature);
        when(joinPoint.getThis()).thenReturn(new Object());

        assertThatExceptionOfType(IllegalStateException.class)
                .isThrownBy(() -> aspect.checkUserAccess(joinPoint, 7L));
    }

    @Test
    void ownAndSharedRowsPassAndAForeignOneIsRefused() {
        when(authenticatedUser.get()).thenReturn(Optional.of(user(1L)));
        JoinPoint joinPoint = mock(JoinPoint.class);
        Signature signature = mock(Signature.class);
        when(joinPoint.getSignature()).thenReturn(signature);

        assertThatNoException().isThrownBy(() -> aspect.checkUserAccess(joinPoint, project(user(1L))));
        assertThatNoException().isThrownBy(() -> aspect.checkUserAccess(joinPoint, project(null)));
        assertThatExceptionOfType(SecurityException.class)
                .isThrownBy(() -> aspect.checkUserAccess(joinPoint, project(user(2L))));
    }

    private static Project project(User owner) {
        Project project = new Project();
        project.owner = owner;
        return project;
    }

    private static User user(long id) {
        User user = new User();
        user.setId(id);
        return user;
    }
}
