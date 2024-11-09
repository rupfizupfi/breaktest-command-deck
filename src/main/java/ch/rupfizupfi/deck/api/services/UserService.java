package ch.rupfizupfi.deck.api.services;

import ch.rupfizupfi.deck.data.User;
import ch.rupfizupfi.deck.data.UserRepository;
import ch.rupfizupfi.deck.hilla.crud.CrudRepositoryService;
import com.vaadin.hilla.BrowserCallable;
import jakarta.annotation.security.RolesAllowed;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

@BrowserCallable
@RolesAllowed("ROLE_ADMIN")
public class UserService extends CrudRepositoryService<User, UserRepository> {

    private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    @Override
    public User save(User user) {
        if (user.getNewPassword() != null && !user.getNewPassword().isEmpty()) {
            user.setHashedPassword(passwordEncoder.encode(user.getNewPassword()));
        }
        return super.save(user);
    }
}