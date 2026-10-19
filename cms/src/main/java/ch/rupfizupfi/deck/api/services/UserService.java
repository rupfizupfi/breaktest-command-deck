package ch.rupfizupfi.deck.api.services;

import ch.rupfizupfi.deck.data.User;
import ch.rupfizupfi.deck.data.UserRepository;
import ch.rupfizupfi.deck.hilla.crud.CrudRepositoryService;
import com.vaadin.hilla.BrowserCallable;
import com.vaadin.hilla.exception.EndpointException;
import jakarta.annotation.security.RolesAllowed;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.ArrayList;
import java.util.List;

@BrowserCallable
// Spring prepends the ROLE_ prefix itself, so this must be the bare role name.
@RolesAllowed("ADMIN")
public class UserService extends CrudRepositoryService<User, UserRepository> {

    private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    /**
     * {@code hashedPassword} is {@code @JsonIgnore}d, so a browser payload never carries it: an
     * edit without a new password keeps the stored hash, and a new user without one is refused.
     */
    @Override
    public User save(User user) {
        if (user.hasNewPassword()) {
            user.setHashedPassword(passwordEncoder.encode(user.getNewPassword()));
        } else if (user.getId() != null) {
            User stored = getRepository().findById(user.getId())
                    .orElseThrow(() -> new EndpointException("No user with id " + user.getId()));
            user.setHashedPassword(stored.getHashedPassword());
        } else {
            throw new EndpointException("A new user needs a password");
        }
        return super.save(user);
    }

    @Override
    public List<User> saveAll(Iterable<User> users) {
        List<User> saved = new ArrayList<>();
        users.forEach(user -> saved.add(this.save(user)));
        return saved;
    }
}
