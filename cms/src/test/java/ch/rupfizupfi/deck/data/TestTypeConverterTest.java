package ch.rupfizupfi.deck.data;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The column keeps the free-form string's bytes; a value outside the three is refused at load (D1). */
class TestTypeConverterTest {

    private final TestTypeConverter converter = new TestTypeConverter();

    @Test
    void everyTypeRoundTripsThroughTheStringItWasStoredAsBefore() {
        assertThat(converter.convertToDatabaseColumn(TestType.DESTRUCTIVE)).isEqualTo("destructive");
        assertThat(converter.convertToDatabaseColumn(TestType.CYCLIC)).isEqualTo("cyclic");
        assertThat(converter.convertToDatabaseColumn(TestType.TIME_CYCLIC)).isEqualTo("timeCyclic");
        for (TestType type : TestType.values()) {
            assertThat(converter.convertToEntityAttribute(converter.convertToDatabaseColumn(type))).isEqualTo(type);
        }
    }

    @Test
    void nullStaysNullBothWays() {
        assertThat(converter.convertToDatabaseColumn(null)).isNull();
        assertThat(converter.convertToEntityAttribute(null)).isNull();
    }

    @Test
    void anUnknownStoredValueIsRefusedNamingIt() {
        assertThatThrownBy(() -> converter.convertToEntityAttribute("fatigue-draft"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("unknown test parameter type: fatigue-draft");
    }

    @Test
    void matchingIsExactSoTheConstantNameIsNotAStoredValue() {
        assertThatThrownBy(() -> converter.convertToEntityAttribute("CYCLIC"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> converter.convertToEntityAttribute("timecyclic"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
