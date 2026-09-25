package ch.rupfizupfi.deck.security;

import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.Signature;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** A Long id on a target that is no {@code CrudRepositoryService} is unresolvable, so the check fails loudly. */
class CheckUserCanOnlyAccessOwnDataAspectTest {

    private CheckUserCanOnlyAccessOwnDataAspect aspect;

    @BeforeEach
    void createAspect() {
        aspect = new CheckUserCanOnlyAccessOwnDataAspect();
        ReflectionTestUtils.setField(aspect, "authenticatedUser", mock(AuthenticatedUser.class));
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
}
