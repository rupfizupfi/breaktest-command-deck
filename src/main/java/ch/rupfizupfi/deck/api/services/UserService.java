package ch.rupfizupfi.deck.api.services;

import ch.rupfizupfi.deck.data.User;
import ch.rupfizupfi.deck.data.UserRepository;
import ch.rupfizupfi.deck.hilla.crud.CrudRepositoryService;
import com.vaadin.hilla.BrowserCallable;
import jakarta.annotation.security.RolesAllowed;

@BrowserCallable
@RolesAllowed("ADMIN")
public class UserService extends CrudRepositoryService<User, UserRepository> {

}