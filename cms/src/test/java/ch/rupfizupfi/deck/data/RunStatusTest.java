package ch.rupfizupfi.deck.data;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.assertj.core.api.Assertions.assertThat;

class RunStatusTest {

    @ParameterizedTest
    @EnumSource(RunStatus.class)
    void terminalityIsDecidedForEveryConstant(RunStatus status) {
        // Exhaustive switch, no default: a new constant fails compilation here until someone
        // decides whether StartupRecoveryRunner must sweep it on boot.
        boolean expected = switch (status) {
            case IN_PROGRESS, INTERRUPTED -> false;
            case COMPLETED, COMPLETED_WITH_GAPS, ABORTED, FAULT -> true;
        };
        assertThat(status.isTerminal()).isEqualTo(expected);
    }
}
