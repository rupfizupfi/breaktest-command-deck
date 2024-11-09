package ch.rupfizupfi.deck.data.serializer;

import ch.rupfizupfi.deck.data.Sample;
import ch.rupfizupfi.deck.data.jsonViews.Views;

public class SimpleSampleSerializer extends ViewSerializer<Sample, Views.Simple> {
    @Override
    Class<Views.Simple> getView() {
        return Views.Simple.class;
    }
}
