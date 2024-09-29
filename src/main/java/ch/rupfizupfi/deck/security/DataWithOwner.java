package ch.rupfizupfi.deck.security;

import ch.rupfizupfi.deck.data.User;
import jakarta.annotation.Nullable;

public interface DataWithOwner {
    @Nullable
    public User getOwner();
}
