package ch.rupfizupfi.deck.api;

import ch.rupfizupfi.deck.data.User;
import ch.rupfizupfi.deck.data.UserRepository;
import ch.rupfizupfi.deck.security.AuthenticatedUser;
import ch.rupfizupfi.deck.security.UserUtils;
import com.vaadin.flow.server.auth.AnonymousAllowed;
import com.vaadin.hilla.Endpoint;
import com.vaadin.hilla.Nonnull;
import com.vaadin.hilla.Nullable;
import com.vaadin.hilla.crud.JpaFilterConverter;
import com.vaadin.hilla.crud.filter.Filter;
import jakarta.validation.constraints.NotNull;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;

import java.util.List;
import java.util.Optional;

@Endpoint
@AnonymousAllowed
public class UserEndpoint {
    protected UserRepository userRepository;

    UserEndpoint(UserRepository userRepository) {
        super();
        this.userRepository = userRepository;
    }

    @Autowired
    private AuthenticatedUser authenticatedUser;

    public Optional<User> getAuthenticatedUser() {
        return authenticatedUser.get();
    }

    @Nonnull
    public @NotNull List<@Nonnull User> list(Pageable pageable, @Nullable Filter filter) {
        if (UserUtils.isAdmin()) {
            Specification<User> spec = JpaFilterConverter.toSpec(filter, User.class);
            return userRepository.findAll(spec, pageable).getContent();
        }

        User user = authenticatedUser.get().orElse(null);
        return user == null ? List.of() : List.of(user);
    }
}
