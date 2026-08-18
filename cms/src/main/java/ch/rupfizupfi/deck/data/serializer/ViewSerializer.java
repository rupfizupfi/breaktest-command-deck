package ch.rupfizupfi.deck.data.serializer;

import tools.jackson.core.JsonGenerator;
import tools.jackson.databind.SerializationContext;
import tools.jackson.databind.ValueSerializer;
import tools.jackson.databind.json.JsonMapper;

public abstract class ViewSerializer<M, V> extends ValueSerializer<M> {

    /**
     * Jackson 3 dropped {@code JsonGenerator.getCodec()}, so the projection can no longer
     * borrow the enclosing mapper. A dedicated mapper renders the view instead.
     */
    private static final JsonMapper VIEW_MAPPER = JsonMapper.builder().findAndAddModules().build();

    abstract Class<V> getView();

    @Override
    public void serialize(M model, JsonGenerator gen, SerializationContext ctxt) {
        gen.writeRawValue(VIEW_MAPPER.writerWithView(getView()).writeValueAsString(model));
    }
}
