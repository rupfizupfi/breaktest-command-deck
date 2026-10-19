package ch.rupfizupfi.deck.device.simulated;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.SplittableRandom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

class SpecimenTest {

    /** Simulated crosshead travel at speed 350, the speed the measured runs used. */
    private static final double MM_PER_SECOND_AT_SPEED_350 = 350 / 60.0;

    @ParameterizedTest
    @EnumSource(SpecimenProfile.class)
    void aFreshSpecimenSitsAtItsPretension(SpecimenProfile profile) {
        Specimen specimen = Specimen.nominal(profile);

        assertThat(specimen.forceAt(specimen.mountPositionMm()))
                .isCloseTo(profile.pretensionNewton(), within(1e-6));
        // Above every lower shut-off threshold the bench has used, or a destructive run ends at once.
        assertThat(profile.pretensionNewton()).isGreaterThan(1000);
    }

    @ParameterizedTest
    @EnumSource(SpecimenProfile.class)
    void aNominalSpecimenBreaksAtTheMeasuredForceAfterTheMeasuredTravel(SpecimenProfile profile) {
        Specimen specimen = Specimen.nominal(profile);

        double atBreak = specimen.mountPositionMm() + profile.breakExtensionMm();
        assertThat(specimen.forceAt(atBreak)).isCloseTo(profile.breakForceNewton(), within(1e-6));
    }

    @ParameterizedTest
    @EnumSource(SpecimenProfile.class)
    void forceRisesMonotonicallyFromZero(SpecimenProfile profile) {
        Specimen specimen = Specimen.nominal(profile);
        double end = specimen.mountPositionMm() + 1.5 * profile.breakExtensionMm();

        assertThat(specimen.forceAt(0)).isZero();
        double previous = 0;
        for (double position = 0.5; position <= end; position += 0.5) {
            double force = specimen.forceAt(position);
            assertThat(force).as("force at %s mm", position).isGreaterThanOrEqualTo(previous);
            previous = force;
        }
        // Past the nominal break the curve keeps loading, so a stronger draw still breaks.
        assertThat(previous).isGreaterThan(profile.breakForceNewton());
    }

    @Test
    void theDefaultProfileReproducesTheMedianRunAtSpeed350() {
        Specimen specimen = Specimen.nominal(SpecimenProfile.DEFAULT);

        double secondsToBreak = SpecimenProfile.DEFAULT.breakExtensionMm() / MM_PER_SECOND_AT_SPEED_350;
        assertThat(secondsToBreak).isCloseTo(65, within(1.0));
        // Measured J-curve: a third of the way from pretension to break at half the travel.
        double halfway = specimen.mountPositionMm() + SpecimenProfile.DEFAULT.breakExtensionMm() / 2;
        double fraction = (specimen.forceAt(halfway) - SpecimenProfile.DEFAULT.pretensionNewton())
                / (SpecimenProfile.DEFAULT.breakForceNewton() - SpecimenProfile.DEFAULT.pretensionNewton());
        assertThat(fraction).isCloseTo(0.330, within(1e-9));
    }

    @Test
    void drawnSpecimensScatterInsideTwoSigma() {
        SpecimenProfile profile = SpecimenProfile.STIFF;
        var random = new SplittableRandom(42);
        double sigma = profile.breakForceSpread() * profile.breakForceNewton();

        double min = Double.MAX_VALUE;
        double max = 0;
        for (int i = 0; i < 1000; i++) {
            double breakForce = Specimen.draw(profile, profile.pretensionNewton(), random).breakForceNewton();
            min = Math.min(min, breakForce);
            max = Math.max(max, breakForce);
        }

        assertThat(min).isGreaterThanOrEqualTo(profile.breakForceNewton() - 2 * sigma - 1e-6);
        assertThat(max).isLessThanOrEqualTo(profile.breakForceNewton() + 2 * sigma + 1e-6);
        assertThat(max - min).isGreaterThan(sigma);
    }

    @Test
    void profilesResolveByKeyConstantOrMaterialName() {
        assertThat(SpecimenProfile.resolve("")).isEqualTo(SpecimenProfile.DEFAULT);
        assertThat(SpecimenProfile.resolve("stiff-low-strength")).isEqualTo(SpecimenProfile.STIFF_LOW_STRENGTH);
        assertThat(SpecimenProfile.resolve("POLYAMIDE_WEBBING_DEGRADED"))
                .isEqualTo(SpecimenProfile.POLYAMIDE_WEBBING_DEGRADED);
        assertThat(SpecimenProfile.resolve(" polyamid ")).isEqualTo(SpecimenProfile.POLYAMIDE_WEBBING);
    }

    @Test
    void anUnknownProfileFailsInsteadOfFallingBack() {
        // Steel was an invented preset; no run measured it.
        assertThatThrownBy(() -> SpecimenProfile.resolve("Steel"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("compliant");
    }
}
