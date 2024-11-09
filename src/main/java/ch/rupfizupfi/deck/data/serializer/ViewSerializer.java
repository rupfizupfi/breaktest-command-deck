package ch.rupfizupfi.deck.data.serializer;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.fasterxml.jackson.databind.SerializerProvider;

import java.io.IOException;

public abstract class ViewSerializer<M, V> extends JsonSerializer<M> {
    abstract Class<V> getView();

    @Override
    public void serialize(M model, JsonGenerator gen, SerializerProvider serializers) throws IOException {
        ObjectMapper mapper = (ObjectMapper) gen.getCodec();
        ObjectWriter writer = mapper.writerWithView(getView());
        gen.writeRawValue(writer.writeValueAsString(model));
    }
}
