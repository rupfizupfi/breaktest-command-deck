package ch.rupfizupfi.deck.rig;

import ch.rupfizupfi.deck.data.Setting;
import ch.rupfizupfi.deck.data.SettingRepository;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;

/** Valid by construction, and read from the settings without ever falling back past a bad value. */
class RigLimitsTest {

    @Test
    void theDefaultsAreTheOwnerConfirmedCellAndTheConservativeSpeed() {
        assertThat(RigLimits.DEFAULTS.ratedCapacityKilonewton()).isEqualTo(150.0);
        assertThat(RigLimits.DEFAULTS.ratedCapacityNewton()).isEqualTo(150_000.0);
        assertThat(RigLimits.DEFAULTS.maxSpeedMillimetresPerMinute()).isEqualTo(500.0);
    }

    @Test
    void outOfRangeOrNonFiniteValuesAreRefused() {
        assertThatThrownBy(() -> new RigLimits(0, 500)).hasMessageContaining("rated capacity");
        assertThatThrownBy(() -> new RigLimits(1_001, 500)).hasMessageContaining("rated capacity");
        assertThatThrownBy(() -> new RigLimits(Double.NaN, 500)).hasMessageContaining("rated capacity");
        assertThatThrownBy(() -> new RigLimits(150, -1)).hasMessageContaining("maximum crosshead speed");
        assertThatThrownBy(() -> new RigLimits(150, Double.POSITIVE_INFINITY)).hasMessageContaining("maximum crosshead speed");
        assertThatThrownBy(() -> new RigLimits(150, 1_001)).hasMessageContaining("maximum crosshead speed");
    }

    @Test
    void readTakesEveryLimitFromTheSettings() {
        assertThat(RigLimits.read(settings(120, 300, 750))).isEqualTo(new RigLimits(120, 300, 750));
    }

    @Test
    void theSuctionLoadMarginDefaultsTo500NewtonAndTheShortConstructorKeepsIt() {
        assertThat(RigLimits.DEFAULTS.suctionLoadMarginNewton()).isEqualTo(500.0);
        assertThat(new RigLimits(120, 300).suctionLoadMarginNewton()).isEqualTo(500.0);
    }

    @Test
    void aSuctionLoadMarginFromZeroUpToTheRatedCapacityIsAccepted() {
        assertThat(new RigLimits(150, 500, 0).suctionLoadMarginNewton()).isZero();
        assertThat(new RigLimits(150, 500, 150_000).suctionLoadMarginNewton()).isEqualTo(150_000.0);
    }

    @Test
    void aSuctionLoadMarginNegativeNotFiniteOrAboveTheRatedCapacityIsRefused() {
        assertThatThrownBy(() -> new RigLimits(150, 500, -1)).hasMessageContaining("suction load margin");
        assertThatThrownBy(() -> new RigLimits(150, 500, Double.NaN)).hasMessageContaining("suction load margin");
        assertThatThrownBy(() -> new RigLimits(150, 500, Double.POSITIVE_INFINITY))
                .hasMessageContaining("suction load margin");
        assertThatThrownBy(() -> new RigLimits(10, 500, 10_001)).hasMessageContaining("suction load margin");
    }

    @Test
    void aStoredSuctionLoadMarginThatIsNoNumberIsRefusedNamingTheKey() {
        SettingRepository settings = settings(150, 300, "lots");

        assertThatThrownBy(() -> RigLimits.read(settings))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(Setting.Key.RIG_SUCTION_LOAD_MARGIN.getKey());
    }

    @Test
    void aStoredValueThatIsNoNumberIsRefusedNamingTheKey() {
        SettingRepository settings = settings("lots", 300);

        assertThatThrownBy(() -> RigLimits.read(settings))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(Setting.Key.RIG_RATED_CAPACITY.getKey());
    }

    @Test
    void aStoredValueOutOfRangeIsRefusedNotReplacedByTheDefault() {
        SettingRepository settings = settings(150, 0);

        assertThatThrownBy(() -> RigLimits.read(settings))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("maximum crosshead speed");
    }

    private static SettingRepository settings(Object capacity, Object speed) {
        return settings(capacity, speed, RigLimits.DEFAULT_SUCTION_LOAD_MARGIN_NEWTON);
    }

    private static SettingRepository settings(Object capacity, Object speed, Object margin) {
        SettingRepository settings = mock(SettingRepository.class);
        doReturn(Setting.create(Setting.Key.RIG_RATED_CAPACITY, capacity))
                .when(settings).getSetting(Setting.Key.RIG_RATED_CAPACITY.getKey());
        doReturn(Setting.create(Setting.Key.RIG_MAX_SPEED, speed))
                .when(settings).getSetting(Setting.Key.RIG_MAX_SPEED.getKey());
        doReturn(Setting.create(Setting.Key.RIG_SUCTION_LOAD_MARGIN, margin))
                .when(settings).getSetting(Setting.Key.RIG_SUCTION_LOAD_MARGIN.getKey());
        return settings;
    }
}
