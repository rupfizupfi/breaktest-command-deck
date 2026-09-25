package ch.rupfizupfi.deck.security;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.authorization.AuthorizationResult;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A Hilla view's {@code rolesAllowed} names {@code Role} enum values, so the route rule has to
 * add the {@code ROLE_} prefix before it matches a granted authority.
 */
class HillaRouteAuthorizationTest {

    private static final RequestAuthorizationContext CONTEXT =
            new RequestAuthorizationContext(new MockHttpServletRequest());

    private static final Authentication ADMIN =
            new TestingAuthenticationToken("admin", "n/a", "ROLE_ADMIN");

    private static final Authentication USER =
            new TestingAuthenticationToken("user", "n/a", "ROLE_USER");

    private static final Authentication ANONYMOUS = new AnonymousAuthenticationToken(
            "key", "anonymousUser", AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS"));

    private static AuthorizationResult authorize(Set<String> roles, Authentication authentication) {
        AuthorizationManager<RequestAuthorizationContext> manager =
                SecurityConfiguration.hillaRouteAccess(request -> roles);
        return manager.authorize(() -> authentication, CONTEXT);
    }

    @Test
    void anEnumNamedRoleMatchesThePrefixedAuthority() {
        assertThat(authorize(Set.of("ADMIN"), ADMIN).isGranted()).isTrue();
    }

    @Test
    void anotherRoleIsRefused() {
        assertThat(authorize(Set.of("ADMIN"), USER).isGranted()).isFalse();
    }

    @Test
    void anAlreadyPrefixedRoleIsNotPrefixedTwice() {
        assertThat(authorize(Set.of("ROLE_ADMIN"), ADMIN).isGranted()).isTrue();
    }

    @Test
    void aRouteNamingNoRoleAdmitsAnyAuthenticatedUser() {
        assertThat(authorize(Set.of(), USER).isGranted()).isTrue();
    }

    @Test
    void aRouteNamingNoRoleRefusesAnonymous() {
        assertThat(authorize(Set.of(), ANONYMOUS).isGranted()).isFalse();
    }
}
