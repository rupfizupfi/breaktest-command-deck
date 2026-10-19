package ch.rupfizupfi.deck.testrunner;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DriveLinkPropertiesTest {

    @Test
    void defaultsToTwoSeconds() {
        assertThat(new DriveLinkProperties().requireLinkLossTimeout()).isEqualTo(Duration.ofSeconds(2));
    }

    @Test
    void bindsTheKebabCaseProperty() {
        var source = new MapConfigurationPropertySource(Map.of("deck.drive.link-loss-timeout", "3s"));

        var bound = new Binder(source).bind("deck.drive", DriveLinkProperties.class).get();

        assertThat(bound.getLinkLossTimeout()).isEqualTo(Duration.ofSeconds(3));
    }

    @Test
    void theBoundsAreInclusive() {
        var properties = new DriveLinkProperties();

        properties.setLinkLossTimeout(Duration.ofSeconds(1));
        assertThat(properties.isLinkLossTimeoutInRange()).isTrue();
        properties.setLinkLossTimeout(Duration.ofSeconds(10));
        assertThat(properties.isLinkLossTimeoutInRange()).isTrue();
    }

    @Test
    void aTimeoutOutsideTheBoundsNeverReachesTheDrive() {
        var properties = new DriveLinkProperties();

        properties.setLinkLossTimeout(Duration.ofMillis(999));
        assertThat(properties.isLinkLossTimeoutInRange()).isFalse();
        assertThatThrownBy(properties::requireLinkLossTimeout).isInstanceOf(IllegalStateException.class);

        properties.setLinkLossTimeout(Duration.ofMillis(10_001));
        assertThatThrownBy(properties::requireLinkLossTimeout).isInstanceOf(IllegalStateException.class);

        properties.setLinkLossTimeout(null);
        assertThatThrownBy(properties::requireLinkLossTimeout).isInstanceOf(IllegalStateException.class);
    }
}
