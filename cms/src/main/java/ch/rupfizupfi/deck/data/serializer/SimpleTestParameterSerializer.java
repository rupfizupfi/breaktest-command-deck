package ch.rupfizupfi.deck.data.serializer;

import ch.rupfizupfi.deck.data.TestParameter;
import ch.rupfizupfi.deck.data.jsonViews.Views;

public class SimpleTestParameterSerializer extends ViewSerializer<TestParameter, Views.Simple> {
    @Override
    Class<Views.Simple> getView() {
        return Views.Simple.class;
    }
}
