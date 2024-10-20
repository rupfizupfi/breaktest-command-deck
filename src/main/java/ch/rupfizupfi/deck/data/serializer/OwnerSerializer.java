package ch.rupfizupfi.deck.data.serializer;

import ch.rupfizupfi.deck.data.User;
import ch.rupfizupfi.deck.data.jsonViews.Views;

public class OwnerSerializer extends ViewSerializer<User, Views.Simple> {
    @Override
    Class<Views.Simple> getView() {
        return Views.Simple.class;
    }
}