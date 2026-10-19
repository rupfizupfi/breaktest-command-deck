package ch.rupfizupfi.deck.testrunner.program;

import ch.rupfizupfi.deck.data.Setting;
import ch.rupfizupfi.deck.data.SettingRepository;
import ch.rupfizupfi.deck.data.TestParameter;
import ch.rupfizupfi.deck.testrunner.run.Direction;
import ch.rupfizupfi.deck.testrunner.run.FinishCause;
import ch.rupfizupfi.deck.testrunner.run.Limit;
import ch.rupfizupfi.deck.testrunner.run.MotorStop;
import ch.rupfizupfi.deck.testrunner.run.Verdict;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.InOrder;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The destructive program, pure: its declaration and what its verdict and finish hook decide.
 * That the motor stops before the finish hook runs (H-19), and that the finish stop's outcome
 * reaches it, is the run loop's part, held by {@code RunLoopTest}.
 */
class DestructiveProgramTest {

    /** The bench setting's default. */
    private static final double MARGIN = 500;
    /** Lower shut-off 1000 N plus the margin. */
    private static final float LOAD_BUILT = 1500f;
    private static final String UPPER_SKIP = "suction skipped: upper shut-off reached, specimen held";
    private static final String UNCONFIRMED_SKIP = "suction skipped: motor stop unconfirmed, use E-stop";

    private SettingRepository settings;
    private final List<Integer> suctionStarts = new ArrayList<>();
    private DestructiveProgram program;
    private FakeRunControl control;

    @BeforeEach
    void createProgram() {
        settings = mock(SettingRepository.class);
        when(settings.getSettingValue(Setting.Key.TESTRUNNER_SUCK)).thenReturn(true);
        when(settings.getSettingValue(Setting.Key.TESTRUNNER_SUCK_DURATION)).thenReturn(3);
        program = programWithLower(1.0);
    }

    private DestructiveProgram programWithLower(double lowerKilonewton) {
        TestParameter parameter = new TestParameter();
        parameter.speed = 500;
        parameter.upperShutOffThreshold = 7.0;
        parameter.lowerShutOffThreshold = lowerKilonewton;
        DestructiveProgram created = new DestructiveProgram(parameter, MARGIN, settings, suctionStarts::add);
        control = new FakeRunControl(created.limits()).energized(created.startup());
        return created;
    }

    /** The run as the loop drives it: a crossing, the peak the envelope folded, then the finish. */
    private void finishOn(Limit limit, float peak, MotorStop stop) {
        program.onLimit(limit, control);
        control.max = peak;
        program.onFinished(FinishCause.LIMIT, stop, control);
    }

    private static String noLoadSkip(String peak) {
        return "suction skipped: no load built (peak " + peak + " N, a break needs 1500.0 N: the lower shut-off"
                + " plus the suction load margin) - the test was not started correctly";
    }

    @Test
    void itPullsOnTheFirstRampToItsShutOffThresholdsWithNoCeilingBeyondThem() {
        assertThat(program.limits()).isEqualTo(new Limits(7000.0, 1000.0, Double.POSITIVE_INFINITY));
        // 500 mm/min / 0.375 mm/rev (OQ-63).
        assertThat(program.startup()).isEqualTo(new DriveSetup(1333, Direction.PULL, null));
        assertThat(program.name()).isEqualTo("DestructiveTest");
    }

    @Test
    void itResetsTheEnvelopeMaximumToZeroThenAnnouncesItsThresholdsAsTheRunLogAlwaysHadThem() {
        program.onStart(control);

        // The measurement loop seeds the maximum at the upper limit; the finish reads it as the peak.
        assertThat(control.calls.getFirst()).isEqualTo("resetEnvelopeMax 0.0");
        assertThat(control.logLines()).containsExactly("upperShutOffThreshold 7000.0 Newton",
                "lowerShutOffThreshold 1000.0 Newton", "Destructive test start");
    }

    @ParameterizedTest
    @EnumSource(Limit.class)
    void eitherShutOffCrossingFinishesTheRunWithoutTouchingTheDrive(Limit limit) {
        assertThat(program.onLimit(limit, control)).isEqualTo(Verdict.finish(FinishCause.LIMIT));

        // The run loop owns the stop: the verdict itself moves nothing.
        assertThat(control.calls).isEmpty();
        assertThat(suctionStarts).isEmpty();
    }

    @Test
    void itIsAbortOnly() {
        assertThat(program.resumePolicy()).isSameAs(ResumePolicy.ABORT_ONLY);
    }

    /** Owner decision 2026-10-13, rule B: limit x load built x motor stop. */
    static Stream<Arguments> suctionTruthTable() {
        List<Arguments> rows = new ArrayList<>();
        for (MotorStop stop : MotorStop.values()) {
            // The specimen held to the shut-off: never a break, whatever the stop.
            rows.add(arguments(Limit.UPPER, 8000f, stop, null, UPPER_SKIP));
            // Never loaded: not clamped or not pretensioned.
            rows.add(arguments(Limit.LOWER, 900f, stop, null, noLoadSkip("900.0")));
            rows.add(arguments(Limit.LOWER, LOAD_BUILT - 0.5f, stop, null, noLoadSkip("1499.5")));
        }
        rows.add(arguments(Limit.LOWER, LOAD_BUILT, MotorStop.CONFIRMED, 3, null));
        rows.add(arguments(Limit.LOWER, 34_000f, MotorStop.CONFIRMED, 3, null));
        // Coasting counts as stopped (owner): after a break there is no load left to pull.
        rows.add(arguments(Limit.LOWER, 34_000f, MotorStop.COASTING, 3, null));
        // A crossing reaches the program only after the energize; no motor ran, so nothing pulls.
        rows.add(arguments(Limit.LOWER, 34_000f, MotorStop.NEVER_ENERGIZED, 3, null));
        rows.add(arguments(Limit.LOWER, 34_000f, MotorStop.UNCONFIRMED, null, UNCONFIRMED_SKIP));
        return rows.stream();
    }

    @ParameterizedTest(name = "{0} peak {1} stop {2} -> suction {3}")
    @MethodSource("suctionTruthTable")
    void onlyABreakAfterBuiltLoadWithTheMotorStoppedStartsTheSuction(Limit limit, float peak, MotorStop stop,
                                                                     Integer suctionSeconds, String skipLine) {
        finishOn(limit, peak, stop);

        if (suctionSeconds == null) {
            assertThat(suctionStarts).isEmpty();
            verify(settings, never()).getSettingValue(Setting.Key.TESTRUNNER_SUCK);
            assertThat(control.logLines()).containsExactly(skipLine);
        } else {
            assertThat(suctionStarts).containsExactly(suctionSeconds);
            assertThat(control.logLines()).isEmpty();
        }
        // Read, never moved: the finish hook acts on the run only through its log.
        assertThat(control.calls).allMatch(call -> call.equals("envelope") || call.startsWith("log "));
    }

    @Test
    void aLowerShutOffOfZeroStillStartsTheSuctionOnceTheMarginWasReached() {
        program = programWithLower(0.0);

        finishOn(Limit.LOWER, 500f, MotorStop.CONFIRMED);

        assertThat(suctionStarts).containsExactly(3);
    }

    @Test
    void aBreakReadsTheSuctionSettingsThenStartsTheJob() {
        finishOn(Limit.LOWER, 34_000f, MotorStop.CONFIRMED);

        InOrder order = inOrder(settings);
        order.verify(settings).getSettingValue(Setting.Key.TESTRUNNER_SUCK);
        order.verify(settings).getSettingValue(Setting.Key.TESTRUNNER_SUCK_DURATION);
        assertThat(suctionStarts).containsExactly(3);
    }

    @Test
    void suctionSwitchedOffInTheSettingsStartsNoJob() {
        when(settings.getSettingValue(Setting.Key.TESTRUNNER_SUCK)).thenReturn(false);

        finishOn(Limit.LOWER, 34_000f, MotorStop.CONFIRMED);

        assertThat(suctionStarts).isEmpty();
    }

    @ParameterizedTest
    @EnumSource(value = FinishCause.class, names = "LIMIT", mode = EnumSource.Mode.EXCLUDE)
    void anyOtherEndLeavesSuctionAloneAndSaysNothing(FinishCause cause) {
        program.onLimit(Limit.LOWER, control);
        control.max = 34_000f;

        program.onFinished(cause, MotorStop.CONFIRMED, control);

        verify(settings, never()).getSettingValue(Setting.Key.TESTRUNNER_SUCK);
        assertThat(suctionStarts).isEmpty();
        assertThat(control.calls).isEmpty();
    }

    @Test
    void aSuctionFailureIsLoggedNeverThrown() {
        when(settings.getSettingValue(Setting.Key.TESTRUNNER_SUCK)).thenThrow(new IllegalStateException("no row"));

        finishOn(Limit.LOWER, 34_000f, MotorStop.CONFIRMED);

        assertThat(control.logLines()).containsExactly("could not start the suction job: no row");
    }
}
