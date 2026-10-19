package ch.rupfizupfi.deck.device.simulated;

import ch.rupfizupfi.deck.data.TestParameter;
import ch.rupfizupfi.deck.data.TestType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SimulatedSpecimenSelectorTest {

    private final SimulatedBenchProperties properties = new SimulatedBenchProperties();

    private SimulatedSpecimenSelector selector() {
        // select() reads neither; the material query is covered by SimulatedSpecimenSelectorContextTest.
        return new SimulatedSpecimenSelector(new SimulatedBench(properties, new SimulatedFaultSwitches()), null, properties);
    }

    private static TestParameter parameter(TestType type, Double lowerShutOffKiloNewton) {
        TestParameter parameter = new TestParameter();
        parameter.type = type;
        parameter.speed = 350;
        parameter.lowerShutOffThreshold = lowerShutOffKiloNewton;
        return parameter;
    }

    @Test
    void materialsMapCaseInsensitivelyAndTheFirstMappedNameWins() {
        var selection = selector().select(List.of("hemp", "polyester", "POLYAMID"), parameter(TestType.DESTRUCTIVE, 1.0));

        assertThat(selection.profile()).isEqualTo(SpecimenProfile.POLYAMIDE_WEBBING);
        assertThat(selection.reason()).isEqualTo("material POLYAMID");
    }

    @Test
    void theProfilePropertyOverridesTheSample() {
        properties.setProfile("stiff-low-strength");

        var selection = selector().select(List.of("Polyamid"), parameter(TestType.DESTRUCTIVE, 1.0));

        assertThat(selection.profile()).isEqualTo(SpecimenProfile.STIFF_LOW_STRENGTH);
        assertThat(selection.reason()).contains("forced");
    }

    @Test
    void aDestructiveRunKeepsTheMedianPretensionWhenItClearsTheThreshold() {
        var selection = selector().select(List.of(), parameter(TestType.DESTRUCTIVE, 1.0));

        assertThat(selection.profile()).isEqualTo(SpecimenProfile.DEFAULT);
        assertThat(selection.pretensionNewton()).isEqualTo(SpecimenProfile.DEFAULT.pretensionNewton());
    }

    @Test
    void aDestructiveRunIsTensionedOverAHighLowerThreshold() {
        var selection = selector().select(List.of(), parameter(TestType.DESTRUCTIVE, 2.0));

        assertThat(selection.pretensionNewton())
                .isEqualTo(2000 * SimulatedSpecimenSelector.PRETENSION_OVER_LOWER_THRESHOLD);
    }

    @Test
    void theRaisedPretensionStaysBelowTheBreak() {
        var selection = selector().select(List.of("Polyamid"), parameter(TestType.DESTRUCTIVE, 50.0));

        assertThat(selection.pretensionNewton())
                .isLessThan(SpecimenProfile.POLYAMIDE_WEBBING.breakForceNewton());
    }

    @Test
    void aCyclicRunReleasesFirstSoItsPretensionIsLeftAlone() {
        var selection = selector().select(List.of("Polyamid"), parameter(TestType.CYCLIC, 5.0));

        assertThat(selection.pretensionNewton()).isEqualTo(SpecimenProfile.POLYAMIDE_WEBBING.pretensionNewton());
    }

    @Test
    void aMappingToAnUnknownProfileFailsStartup() {
        properties.getMaterialProfiles().put("Hemp", "rope");

        assertThatThrownBy(this::selector)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("rope");
    }
}
