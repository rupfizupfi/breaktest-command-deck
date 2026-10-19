package ch.rupfizupfi.deck.hilla.crud;

import com.vaadin.hilla.exception.EndpointException;

/**
 * A non-admin's write the owner rule refuses. An {@link EndpointException} because only its message
 * reaches the browser's error toast: a {@link SecurityException} would surface as Hilla's generic
 * "execution failure". The message never says whether a row it refuses to resolve exists.
 */
public class OwnerAccessDeniedException extends EndpointException {
    public OwnerAccessDeniedException(String message) {
        super(message);
    }
}
