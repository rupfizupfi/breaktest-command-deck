package ch.rupfizupfi.deck.data;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/**
 * Stores a {@link TestType} as its {@link TestType#wireName()}, the exact string the column held
 * before it was an enum, so the schema and every existing row are unchanged.
 * <p>
 * A stored value outside the three fails the load, naming it, rather than reaching the runner:
 * D1 chose to fix such rows by hand before deploying, not to keep a catch-all constant.
 */
@Converter(autoApply = true)
public class TestTypeConverter implements AttributeConverter<TestType, String> {
    @Override
    public String convertToDatabaseColumn(TestType attribute) {
        return attribute == null ? null : attribute.wireName();
    }

    @Override
    public TestType convertToEntityAttribute(String dbData) {
        return dbData == null ? null : TestType.fromWireName(dbData);
    }
}
