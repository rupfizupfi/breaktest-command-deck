package ch.rupfizupfi.deck.testrunner.measure;

import ch.rupfizupfi.deck.testrunner.MotorSafetyController;
import ch.rupfizupfi.deck.testrunner.RecoveryProperties;
import ch.rupfizupfi.deck.testrunner.GapRecorder;
import ch.rupfizupfi.deck.testrunner.RecoveryGates;
import ch.rupfizupfi.deck.testrunner.SafeStopResult;
import ch.rupfizupfi.deck.device.api.Measurement;
import ch.rupfizupfi.deck.device.loadcell.LoadCellDevice;
import ch.rupfizupfi.deck.filesystem.CSVStoreService;
import ch.rupfizupfi.deck.rig.RigLimits;
import ch.rupfizupfi.deck.testrunner.run.Limit;
import ch.rupfizupfi.deck.testrunner.run.RunEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.InOrder;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyFloat;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MeasurementLoopTest {

    @TempDir
    Path tempDir;

    private ForceLimits context;
    private LoadCellDevice loadCellDevice;
    private MotorSafetyController motorSafety;
    /** Hears every {@link RunEvent.SensorLost} the loop emits, unpacked, for order and argument checks. */
    private LossProbe lossListener;
    /** The run loop's side: every event the loop emits, the loss included. */
    private RunEventSink sink;
    private GapRecorder gapRecorder;
    private Path csvPath;
    private MeasurementLoop cell;
    private boolean started;

    @BeforeEach
    void buildThread() {
        // Context limits double as the seed of the measured min/max envelope.
        context = spy(new ForceLimits(100.0, -100.0, Double.POSITIVE_INFINITY));
        loadCellDevice = mock(LoadCellDevice.class);
        motorSafety = mock(MotorSafetyController.class);
        lossListener = mock(LossProbe.class);
        sink = mock(RunEventSink.class);
        gapRecorder = mock(GapRecorder.class);
        when(motorSafety.safeStop(anyString())).thenReturn(new SafeStopResult(
                SafeStopResult.Tier.EXISTING_HANDLE, true, true, true, 0, "stubbed stop"));
        csvPath = tempDir.resolve("run_force.csv");
        cell = threadWith(new RecoveryProperties().snapshot());
    }

    /** Rebuilds the loop on limits with a ceiling; the ceiling is fixed for a run. */
    private void withCeiling(double ceiling) {
        context = spy(new ForceLimits(100.0, -100.0, ceiling));
        cell = threadWith(new RecoveryProperties().snapshot());
    }

    private MeasurementLoop threadWith(RecoveryGates gates) {
        return threadWith(gates, RigLimits.DEFAULTS);
    }

    private MeasurementLoop threadWith(RecoveryGates gates, RigLimits rigLimits) {
        return new MeasurementLoop(context, loadCellDevice,
                new CSVStoreService.TestRunFiles(csvPath.toString(),
                        tempDir.resolve("run_gaps.json").toString()),
                motorSafety::safeStop, event -> {
                    if (event instanceof RunEvent.SensorLost lost) {
                        lossListener.onSensorLoss(lost.reason(), lost.lastKnownForce());
                    }
                    sink.accept(event);
                }, gates, rigLimits, gapRecorder);
    }

    /** The loss as the run loop receives it. */
    interface LossProbe {
        void onSensorLoss(String reason, float lastKnownForce);
    }

    /**
     * Waits for the {@code n}-th loss event. Not {@code isHolding()}: the loop parks before it tells
     * the run, so the park alone does not prove the event left.
     */
    private void awaitLossEvents(int n) {
        quickly().untilAsserted(() -> verify(lossListener, times(n)).onSensorLoss(anyString(), anyFloat()));
    }

    /**
     * Rebuilds the thread around a gate window of its own, with the gate's sample count at its
     * two-sample minimum: these scenarios are about time, drift and freezing, and the count has its
     * own tests. The knobs are a snapshot taken at construction, so a scenario that needs different
     * ones needs a new thread. Call before start().
     */
    private void useGateWindow(long plausibilityGateMillis) {
        useGate(plausibilityGateMillis, 2);
    }

    private void useGate(long plausibilityGateMillis, int minSamples) {
        RecoveryProperties tuned = new RecoveryProperties();
        tuned.setPlausibilityGateMillis(plausibilityGateMillis);
        tuned.setPlausibilityGateMinSamples(minSamples);
        cell = threadWith(tuned.snapshot());
    }

    /** start() spawns a NON-daemon thread and stop() joins only 100 ms, so every started loop must be
     * proven dead here or the test JVM can hang on exit. */
    @AfterEach
    void killMeasurementLoop() throws Exception {
        if (!started) {
            return;
        }
        Thread loop = cell.loopThread();
        cell.stop();
        if (loop != null) {
            loop.join(2_000);
            assertThat(loop.isAlive()).as("measurement loop must die").isFalse();
        }
    }

    private void startLoop() {
        started = true;
        cell.start();
    }

    /** Trips the 3-of-5 plausibility vote with one poisoned batch and waits for the park. */
    private void tripSensorLoss() {
        cell.update(batch(Float.NaN, 1f, Float.NaN, 2f, Float.NaN));
        quickly().until(cell::isHolding);
        awaitLossEvents(1);
    }

    private static RunEvent crossing(Limit limit) {
        return argThat(event -> event instanceof RunEvent.LimitCrossed crossed && crossed.limit() == limit);
    }

    private static RunEvent anyCrossing() {
        return any(RunEvent.LimitCrossed.class);
    }

    private static RunEvent watchdogFailure(String detail) {
        return argThat(event -> event instanceof RunEvent.WatchdogFailed failed && failed.detail().contains(detail));
    }

    private static RunEvent ceilingTrip() {
        return argThat(event -> event instanceof RunEvent.CeilingTripped trip
                && trip.detail().contains("exceeded the upper shut-off"));
    }

    private static List<Measurement> batch(float... forces) {
        List<Measurement> measurements = new ArrayList<>();
        for (float force : forces) {
            measurements.add(new Measurement(force, System.currentTimeMillis()));
        }
        return measurements;
    }

    /** Fast polling: several assertions must complete inside the 250 ms no-data window. */
    private static org.awaitility.core.ConditionFactory quickly() {
        return await().pollDelay(Duration.ZERO).pollInterval(Duration.ofMillis(5))
                .atMost(Duration.ofSeconds(2));
    }

    @Test
    void oneNanSampleDoesNotPoisonTheMeasuredEnvelope() {
        // One plausibility vote, two short of the 3-of-5 trip: the run continues.
        assertThat(cell.inspectSample(Float.NaN)).isNull();
        assertThat(cell.inspectSample(150f)).isNull();
        assertThat(cell.inspectSample(-150f)).isNull();

        // NaN-absorbing Math.min/max would pin both bounds at NaN and disable the cyclic programs'
        // PULL/RELEASE limit derivation.
        assertThat(cell.getMaxValue()).isEqualTo(150f);
        assertThat(cell.getMinValue()).isEqualTo(-150f);
    }

    @Test
    void anEnvelopeResetIsPostedToTheLoopAndReadsAsAppliedAtOnce() {
        assertThat(cell.inspectSample(-150f)).isNull();
        assertThat(cell.inspectSample(150f)).isNull();

        // The runner's reset never writes the envelope itself, so it cannot be lost against the
        // loop's read-modify-write; until the loop consumes it, the getters report it.
        cell.setMinValue(90f);
        cell.setMaxValue(-90f);
        assertThat(cell.getMinValue()).isEqualTo(90f);
        assertThat(cell.getMaxValue()).isEqualTo(-90f);

        assertThat(cell.inspectSample(50f)).isNull();
        assertThat(cell.inspectSample(80f)).isNull();

        // Applied before the first post-reset fold, then folded as usual.
        assertThat(cell.getMinValue()).isEqualTo(50f);
        assertThat(cell.getMaxValue()).isEqualTo(80f);
    }

    @Test
    void theLatestOfTwoUnconsumedResetsWins() {
        cell.setMinValue(70f);
        cell.setMinValue(60f);

        assertThat(cell.inspectSample(65f)).isNull();

        assertThat(cell.getMinValue()).isEqualTo(60f);
    }

    @Test
    void anImplausibleSampleStillConsumesThePostedResetWithoutFoldingItself() {
        cell.setMaxValue(10f);

        // 1.5 x the 150 kN default rig: implausible, one vote, not folded.
        assertThat(cell.inspectSample(300_000f)).isNull();

        assertThat(cell.getMaxValue()).isEqualTo(10f);
        assertThat(cell.inspectSample(20f)).isNull();
        assertThat(cell.getMaxValue()).isEqualTo(20f);
    }

    @Test
    void theMagnitudeBoundFollowsTheRunsRatedCapacity() {
        // 20 kN is plausible on the 150 kN default rig, but past 1.5 x a 10 kN cell.
        for (int i = 0; i < 3; i++) {
            assertThat(cell.inspectSample(20_000f)).isNull();
        }

        cell = threadWith(new RecoveryProperties().snapshot(), new RigLimits(10, 500));
        assertThat(cell.inspectSample(20_000f)).isNull();
        assertThat(cell.inspectSample(20_000f)).isNull();
        assertThat(cell.inspectSample(20_000f)).contains("implausible");
    }

    @Test
    void fractureSizedDropNeverVotesImplausibleButAnEqualRiseDoes() {
        // A fast drop is a specimen breaking, so only rises may vote; with Math.abs the 4th sample
        // below would already trip 3-of-5.
        assertThat(cell.inspectSample(100_000f)).isNull();
        assertThat(cell.inspectSample(10f)).isNull();       // drop: no vote
        assertThat(cell.inspectSample(100_000f)).isNull();  // rise: vote 1
        assertThat(cell.inspectSample(10f)).isNull();       // drop: no vote (abs() would trip here)
        assertThat(cell.inspectSample(100_000f)).isNull();  // rise: vote 2
        assertThat(cell.inspectSample(10f)).isNull();       // drop: no vote
        assertThat(cell.inspectSample(100_000f)).contains("implausible"); // rise: vote 3 of last 5
    }

    @Test
    void frozenDetectorTripsAfterAHundredBitIdenticalRepeats() {
        assertThat(cell.inspectSample(42.5f)).isNull();
        for (int i = 0; i < 99; i++) {
            assertThat(cell.inspectSample(42.5f)).isNull();
        }
        assertThat(cell.inspectSample(42.5f)).contains("frozen");
    }

    private static long millis(long ms) {
        return TimeUnit.MILLISECONDS.toNanos(ms);
    }

    @Test
    void aSlowFrozenFeedTripsOnTheTimeBoundLongBeforeAHundredSamples() {
        // 80 ms per sample is inside the 100 ms rate floor, so the sample-interval detector never
        // votes; counted, it would take 100 x 80 ms = 8 s. The 1000 ms time bound trips it instead.
        assertThat(cell.inspectSample(42.5f, millis(80))).isNull();
        for (int i = 1; i <= 12; i++) {
            assertThat(cell.inspectSample(42.5f, millis(80))).as("repeat %d", i).isNull();
        }
        // 960 ms of one value is inside the bound; the 13th repeat, 1040 ms after the first, is not.
        assertThat(cell.inspectSample(42.5f, millis(80)))
                .contains("force frozen at 42.5 N for 13 consecutive bit-identical samples over 1040 ms");
    }

    @Test
    void theFrozenTimeBoundIsTheConfiguredOne() {
        RecoveryProperties tuned = new RecoveryProperties();
        tuned.setMaxFrozenMillis(400);
        cell = threadWith(tuned.snapshot());

        // 5 Hz with the bound lowered to 400 ms: the second repeat is the trip.
        assertThat(cell.inspectSample(7f, millis(200))).isNull();
        assertThat(cell.inspectSample(7f, millis(200))).isNull();
        assertThat(cell.inspectSample(7f, millis(200))).contains("frozen").contains("over 400 ms");
    }

    @Test
    void aFastFrozenFeedStillTripsOnTheHundredthRepeat() {
        // The simulator's 5 ms: the count is reached at 500 ms, inside the time bound.
        assertThat(cell.inspectSample(42.5f, millis(5))).isNull();
        for (int i = 1; i < 100; i++) {
            assertThat(cell.inspectSample(42.5f, millis(5))).isNull();
        }
        assertThat(cell.inspectSample(42.5f, millis(5)))
                .contains("for 100 consecutive bit-identical samples over 500 ms");
    }

    @Test
    void aNoisyFeedNeverTripsTheFrozenDetectorHoweverSlowOrLong() {
        // LSB jitter, one sample every 80 ms for 40 s, then every 5 ms for another 10 s.
        float[] jitter = {42.5f, Math.nextUp(42.5f)};
        for (int i = 0; i < 500; i++) {
            assertThat(cell.inspectSample(jitter[i % 2], millis(80))).isNull();
        }
        for (int i = 0; i < 2_000; i++) {
            assertThat(cell.inspectSample(jitter[i % 2], millis(5))).isNull();
        }
    }

    @Test
    void aSingleDifferentSampleRestartsTheFrozenClock() {
        assertThat(cell.inspectSample(42.5f, millis(40))).isNull();
        assertThat(cell.inspectSample(42.5f, millis(960))).isNull();
        // One LSB step ends the run; the time bound counts from the new value's first sample.
        assertThat(cell.inspectSample(Math.nextUp(42.5f), millis(40))).isNull();
        assertThat(cell.inspectSample(Math.nextUp(42.5f), millis(960))).isNull();
        assertThat(cell.inspectSample(Math.nextUp(42.5f), millis(40))).contains("over 1000 ms");
    }

    @Test
    void zeroAndNegativeZeroAreDifferentReadingsNotAFrozenCell() {
        // == would call 0.0f and -0.0f identical; the raw-bits comparison keeps them apart, so a
        // live cell hovering around zero can never read as frozen.
        for (int i = 0; i < 150; i++) {
            assertThat(cell.inspectSample(0.0f)).isNull();
            assertThat(cell.inspectSample(-0.0f)).isNull();
        }
    }

    @Test
    void aStuckNanIsCaughtByBothDetectors() {
        // == never matches NaN, so an equality-based frozen counter would never see a stuck NaN.
        assertThat(cell.inspectSample(Float.NaN)).isNull();
        assertThat(cell.inspectSample(Float.NaN)).isNull();
        // The plausibility vote catches it first, on the third sample...
        assertThat(cell.inspectSample(Float.NaN)).contains("implausible");
        // ...and the raw-bits frozen counter has been counting it all along.
        String reason = null;
        for (int i = 0; i < 98; i++) {
            reason = cell.inspectSample(Float.NaN);
        }
        assertThat(reason).contains("frozen");
    }

    @Test
    void twoImplausibleVotesOfFiveDoNotTrip() {
        assertThat(cell.inspectSample(Float.NaN)).isNull();
        assertThat(cell.inspectSample(1f)).isNull();
        assertThat(cell.inspectSample(Float.NaN)).isNull();
        assertThat(cell.inspectSample(2f)).isNull();
        assertThat(cell.inspectSample(3f)).isNull();
    }

    @Test
    void implausibleVotesAgeOutAfterFiveCleanSamples() {
        assertThat(cell.inspectSample(Float.NaN)).isNull();
        assertThat(cell.inspectSample(Float.NaN)).isNull();
        for (float force : new float[]{1f, 2f, 3f, 4f, 5f}) {
            assertThat(cell.inspectSample(force)).isNull();
        }
        // Without age-out these would be votes 3 and 4 of the run and would trip.
        assertThat(cell.inspectSample(Float.NaN)).isNull();
        assertThat(cell.inspectSample(Float.NaN)).isNull();
    }

    @Test
    void sensorLossStopsTheMotorFirstThenRecordsTheGapThenTellsTheRunLoop() throws Exception {
        startLoop();
        // One batch: the 3-of-5 window advances per sample, so a single drain can trip it.
        cell.update(batch(Float.NaN, 1f, Float.NaN, 2f, Float.NaN));
        quickly().until(cell::isHolding);
        awaitLossEvents(1);

        InOrder escalation = inOrder(motorSafety, gapRecorder, lossListener);
        escalation.verify(motorSafety).safeStop(contains("implausible"));
        escalation.verify(gapRecorder).beginGap(anyString(), anyFloat());
        escalation.verify(lossListener).onSensorLoss(anyString(), anyFloat());
        // The loss reaches the run as an event on the sink, not as a call into the test.
        verify(sink).accept(any(RunEvent.SensorLost.class));

        // Once per incident, and the loop parks instead of exiting so the CSV writer stays open.
        cell.update(batch(Float.NaN, Float.NaN, Float.NaN, Float.NaN, Float.NaN));
        Thread.sleep(100);
        verify(motorSafety, times(1)).safeStop(anyString());
        verify(lossListener, times(1)).onSensorLoss(anyString(), anyFloat());
        assertThat(cell.isHolding()).isTrue();
        assertThat(cell.loopThread().isAlive()).isTrue();
        // The whole faulting batch was still written and flushed - the most interesting rows.
        assertThat(Files.readAllLines(csvPath)).hasSize(5);
    }

    @Test
    void noDataWatchdogIsDisarmedUntilTheFirstSample() throws Exception {
        startLoop();
        Thread.sleep(400); // well past NO_DATA_TIMEOUT_MS without a single sample
        assertThat(cell.isHolding()).isFalse();
        verify(lossListener, never()).onSensorLoss(anyString(), anyFloat());

        cell.update(batch(5f));
        await().atMost(Duration.ofSeconds(2)).until(cell::isHolding);
        awaitLossEvents(1);
        verify(lossListener).onSensorLoss(contains("no measurement"), eq(5f));
    }

    @Test
    void intraBatchCrossingFiresEvenWhenTheLastSampleIsBackInside() {
        startLoop();
        // 150 N crosses the 100 N upper limit in the middle of the drain; the batch ends inside
        // the limits, so only a per-sample decision can see the crossing at all.
        cell.update(batch(50f, 150f, 60f));
        quickly().untilAsserted(() -> verify(sink).accept(crossing(Limit.UPPER)));
    }

    @Test
    void aBatchCrossingBothLimitsEmitsOnlyTheEarlierCrossing() {
        startLoop();
        cell.update(batch(-150f, 150f));
        quickly().untilAsserted(() -> verify(sink).accept(crossing(Limit.LOWER)));

        // No marker batch: the signal goes out once, after the whole drain has been inspected, so
        // seeing it already proves the drain was decided. A marker sent after the wait raced the
        // 250 ms no-data watchdog, whose hold wipes the buffer.
        verify(sink, never()).accept(crossing(Limit.UPPER));
        verify(sink, times(1)).accept(anyCrossing());
    }

    @Test
    void aNanInTheMiddleOfAnInsideBatchDecidesNothing() {
        startLoop();
        // One NaN is one plausibility vote of five, two short of a trip, and it decides no limit:
        // both comparisons are false for it, so an unguarded check would read as inside both.
        // One update, marker included: two could straddle a drain and a test-thread stall past the
        // 250 ms no-data timeout, and the resulting hold would wipe the marker.
        cell.update(batch(50f, Float.NaN, 49f, 48f, 47f));
        quickly().until(() -> cell.getLastForce() == 47f);
        verify(sink, never()).accept(anyCrossing());
    }

    @Test
    void nonFiniteSamplesNeverDecideTheLimit() throws Exception {
        startLoop();
        // One update(), so one atomic drain, so no wall clock is involved: feeding several batches
        // made the test's own scheduling part of the scenario, and a test thread stalled past the
        // 250 ms no-data timeout tripped the watchdog, whose hold then wiped the next batch.
        // +Infinity is above the upper limit, so an unguarded check would claim the drain's one
        // decision as RELEASE at the first infinity. The finite fillers stay inside both limits and
        // hold the plausibility vote at 2 of 5, below its 3-of-5 trip.
        List<Measurement> drain = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            drain.addAll(batch(50f, 49f, 48f, Float.POSITIVE_INFINITY));
        }
        // The guard skips a sample, it does not retire the decision: the one finite crossing, last
        // in the drain and below the lower limit, must still decide it.
        drain.addAll(batch(46f, 45f, 47f, -150f));
        cell.update(drain);

        // The signal is sent once, after the whole drain has been inspected, so seeing it means
        // every infinity above has been decided as well.
        quickly().untilAsserted(() -> verify(sink).accept(crossing(Limit.LOWER)));
        verify(sink, never()).accept(crossing(Limit.UPPER));
        verify(sink, times(1)).accept(anyCrossing());
    }

    @Test
    void recoveryResetReseedsTheEnvelopeFromTheConfiguredLimits() {
        startLoop();
        cell.update(batch(500f, -500f));
        quickly().until(() -> cell.getMaxValue() == 500f && cell.getMinValue() == -500f);
        // The silence after that one batch trips the no-data watchdog and parks the loop.
        await().atMost(Duration.ofSeconds(2)).until(cell::isHolding);

        cell.markSensorRecovered();

        // Re-seeded from the configured limits: the pre-loss envelope describes a specimen that has
        // been creeping through the hold and must not feed the cyclic tests' next limit.
        quickly().until(() -> cell.getMaxValue() == 100f);
        assertThat(cell.getMinValue()).isEqualTo(-100f);
        assertThat(cell.isHolding()).as("the reset alone must not end the hold").isTrue();
    }

    // ---- Recovery gate: pure scoring through the package-private seam (no loop running) ----

    @Test
    void gateRejectsSamplesThatDriftBeyondTheConfiguredFraction() throws Exception {
        useGateWindow(20);
        // driftFraction 0.02 of a 1000 N envelope: only readings within 20 N of the baseline pass.
        CompletableFuture<MeasurementLoop.GateResult> gate = cell.beginRecoveryGate(100f, 1000.0);

        // Drifted samples spanning more than the gate window: every one fails the drift check,
        // so the non-completion is deterministic — a stall only stretches the sleeps.
        for (int i = 0; i < 3; i++) {
            cell.scoreGateSample(150f);
            Thread.sleep(15);
        }
        assertThat(gate).isNotDone();

        // Back inside the drift band the gate opens normally, proving the rejection above was the
        // drift check and not a wedged gate.
        cell.scoreGateSample(101f);
        Thread.sleep(30);
        cell.scoreGateSample(99f);

        assertThat(gate).isDone();
        MeasurementLoop.GateResult result = gate.get();
        assertThat(result.passed()).isTrue();
        // Drift is measured from the first post-reconnect sample, pass or fail: the number needed to
        // retune the drift fraction against a real specimen.
        assertThat(result.firstForce()).isEqualTo(150f);
        assertThat(result.drift()).isEqualTo(50f);
        assertThat(result.driftFraction()).isEqualTo(50f / 1000.0);
    }

    @Test
    void gateTreatsBitIdenticalSamplesAsFrozenNoMatterHowMuchTimePasses() throws Exception {
        useGateWindow(20);
        CompletableFuture<MeasurementLoop.GateResult> gate = cell.beginRecoveryGate(50f, 1000.0);

        // 11 bit-identical samples, back to back so the window cannot expire between them: the run
        // of 10 repeats fails the gate and resets the timer.
        for (int i = 0; i < 11; i++) {
            cell.scoreGateSample(50f);
        }

        // Past the window twice over, so from here only the frozen check holds the gate closed.
        Thread.sleep(30);
        cell.scoreGateSample(50f);
        assertThat(gate).as("bit-identical samples must never satisfy the gate").isNotDone();

        // LSB jitter is what a live strain gauge produces; with it the gate passes normally.
        cell.scoreGateSample(50.1f);
        Thread.sleep(30);
        cell.scoreGateSample(50.2f);
        assertThat(gate).isDone();
        assertThat(gate.get().passed()).isTrue();
    }

    @Test
    void gatePassesOnlyAfterAnUnbrokenPlausibleRunOfTheConfiguredLength() throws Exception {
        useGateWindow(20);
        CompletableFuture<MeasurementLoop.GateResult> gate = cell.beginRecoveryGate(100f, 1000.0);

        // The first acceptable sample starts the run, and the run has zero length at that instant
        // (both nanoTime reads in scoreGateSample are the same value), so it must not pass yet.
        cell.scoreGateSample(100.5f);
        assertThat(gate).isNotDone();

        Thread.sleep(30);
        cell.scoreGateSample(99.5f);

        assertThat(gate).isDone();
        MeasurementLoop.GateResult result = gate.get();
        assertThat(result.passed()).isTrue();
        assertThat(result.detail()).contains("stayed plausible");
    }

    @Test
    void gatePassesOnlyOnceBothTheWindowAndTheSampleCountAreMet() throws Exception {
        useGate(100, 4);
        ManualClock gateClock = new ManualClock();
        cell.useGateClock(gateClock);
        CompletableFuture<MeasurementLoop.GateResult> gate = cell.beginRecoveryGate(100f, 1000.0);

        // Two plausible samples 200 ms apart: twice the window, but short of the count.
        cell.scoreGateSample(100.1f);
        gateClock.advanceMillis(200);
        cell.scoreGateSample(100.2f);
        assertThat(gate).as("two samples, however far apart").isNotDone();
        gateClock.advanceMillis(200);
        cell.scoreGateSample(100.3f);
        assertThat(gate).as("three samples").isNotDone();

        gateClock.advanceMillis(200);
        cell.scoreGateSample(100.4f);
        assertThat(gate).isDone();
        assertThat(gate.get().passed()).isTrue();
        assertThat(gate.get().detail()).contains("stayed plausible for 600 ms over 4 samples");
    }

    @Test
    void theSampleCountAloneDoesNotPassTheGate() {
        useGate(100, 4);
        ManualClock gateClock = new ManualClock();
        cell.useGateClock(gateClock);
        CompletableFuture<MeasurementLoop.GateResult> gate = cell.beginRecoveryGate(100f, 1000.0);

        // Twenty samples at one instant have no length.
        for (int i = 0; i < 20; i++) {
            cell.scoreGateSample(100f + i * 0.01f);
        }
        gateClock.advanceMillis(99);
        cell.scoreGateSample(100.5f);
        assertThat(gate).isNotDone();

        gateClock.advanceMillis(1);
        cell.scoreGateSample(100.6f);
        assertThat(gate).isDone();
    }

    @Test
    void aFailedSampleRestartsTheGatesSampleCount() throws Exception {
        useGate(100, 4);
        ManualClock gateClock = new ManualClock();
        cell.useGateClock(gateClock);
        CompletableFuture<MeasurementLoop.GateResult> gate = cell.beginRecoveryGate(100f, 1000.0);

        cell.scoreGateSample(100.1f);
        cell.scoreGateSample(100.2f);
        cell.scoreGateSample(100.3f);
        gateClock.advanceMillis(200);
        cell.scoreGateSample(150f); // drifted: the run of three is gone with it
        cell.scoreGateSample(100.4f);
        gateClock.advanceMillis(200);
        cell.scoreGateSample(100.5f);
        // Counted across the failure this would be five samples over 400 ms.
        assertThat(gate).as("two samples since the failure").isNotDone();

        cell.scoreGateSample(100.6f);
        cell.scoreGateSample(100.7f);
        assertThat(gate).isDone();
        assertThat(gate.get().detail()).contains("over 4 samples");
    }

    @Test
    void gateKeepsTheSnapshotItWasBuiltWithWhenThePropertiesAreRebound() throws Exception {
        RecoveryProperties properties = new RecoveryProperties();
        properties.setPlausibilityGateMillis(20);
        properties.setPlausibilityGateMinSamples(2);
        cell = threadWith(properties.snapshot());

        // A rebind mid-run must not move the gate the run is already being judged by: a zero drift
        // band rejects every sample, a 600 s window cannot open inside 30 ms, and two samples are
        // not a thousand.
        properties.setPlausibilityGateMillis(600_000);
        properties.setPlausibilityGateMinSamples(1_000);
        properties.setDriftFraction(0.0);

        CompletableFuture<MeasurementLoop.GateResult> gate = cell.beginRecoveryGate(100f, 1000.0);
        cell.scoreGateSample(100.5f);
        Thread.sleep(30);
        cell.scoreGateSample(99.5f);

        assertThat(gate).isDone();
        assertThat(gate.get().passed()).isTrue();
    }

    // ---- Recovery gate: end-to-end through the public surface (loop running) ----

    @Test
    void reconnectedStreamPassesTheGateEndToEndThroughThePublicSurface() throws Exception {
        useGateWindow(50);
        startLoop();
        tripSensorLoss();

        // Same order as SensorReconnector: request the watchdog reset first, then open the gate.
        cell.markSensorRecovered();
        CompletableFuture<MeasurementLoop.GateResult> gate = cell.beginRecoveryGate(50f, 1000.0);

        // Keep-alive feeding with LSB jitter until the verdict - never a fixed number of batches,
        // so a slow drain cannot starve the gate of samples.
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        int i = 0;
        while (!gate.isDone() && System.nanoTime() < deadline) {
            cell.update(batch(50f + (i++ % 3) * 0.1f));
            Thread.sleep(10);
        }

        assertThat(gate).isDone();
        MeasurementLoop.GateResult result = gate.get();
        assertThat(result.passed()).isTrue();
        assertThat(result.firstForce()).isCloseTo(50f, within(1f));
        // Passing the gate alone must not resume limit signals; only endHold() may do that.
        verify(sink, never()).accept(anyCrossing());
    }

    @Test
    void gateDeadlineExpiresWhenTheReconnectedStreamDeliversNothing() throws Exception {
        useGateWindow(20); // deadline = 2 * 20 ms + the 1 s slack
        startLoop();
        tripSensorLoss();

        cell.markSensorRecovered();
        CompletableFuture<MeasurementLoop.GateResult> gate = cell.beginRecoveryGate(50f, 1000.0);
        // No update() is ever fed: the post-reset watchdog stays disarmed until a first sample,
        // so only the gate's own deadline can end it — deterministic, not a timing window.
        MeasurementLoop.GateResult result = gate.get(10, TimeUnit.SECONDS);

        assertThat(result.passed()).isFalse();
        assertThat(result.detail()).contains("no measurement arrived");
        // Only the original loss escalated; a timed-out gate is the reconnector's verdict to act on.
        verify(motorSafety, times(1)).safeStop(anyString());
        verify(lossListener, times(1)).onSensorLoss(anyString(), anyFloat());
    }

    @Test
    void sensorLostAgainDuringTheGateFailsItAndParksTheLoopAgain() throws Exception {
        useGateWindow(600_000); // this gate can only end by losing the sensor
        startLoop();
        tripSensorLoss();

        cell.markSensorRecovered();
        CompletableFuture<MeasurementLoop.GateResult> gate = cell.beginRecoveryGate(50f, 1000.0);
        // The reconnected stream turns out to be as poisoned as the first one.
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!gate.isDone() && System.nanoTime() < deadline) {
            cell.update(batch(Float.NaN, 1f, Float.NaN, 2f, Float.NaN));
            Thread.sleep(10);
        }

        MeasurementLoop.GateResult result = gate.get(1, TimeUnit.SECONDS);
        assertThat(result.passed()).isFalse();
        assertThat(result.detail()).contains("lost again during the gate");
        assertThat(cell.isHolding()).isTrue();
        // The second incident escalated exactly like the first - the reset re-armed the trip latch.
        verify(motorSafety, times(2)).safeStop(anyString());
        verify(lossListener, times(2)).onSensorLoss(anyString(), anyFloat());
    }

    @Test
    void noLimitSignalIsEmittedWhileTheGateIsOpen() throws Exception {
        useGateWindow(600_000); // the gate stays open for the whole test
        startLoop();
        tripSensorLoss();
        cell.markSensorRecovered();
        cell.beginRecoveryGate(50f, 1000.0);

        // 150 N crosses the 100 N upper limit — the crossing the open gate must mute. Re-fed
        // until observed: the loop thread applies markSensorRecovered()'s reset and wipes any
        // batch queued before it, so a one-shot update() racing that wipe is legitimately dropped.
        quickly().until(() -> {
            cell.update(batch(150f));
            return cell.getLastForce() == 150f;
        });
        // A second processed batch proves the first one's limit decision has come and gone.
        quickly().until(() -> {
            cell.update(batch(151f));
            return cell.getLastForce() == 151f;
        });
        verify(sink, never()).accept(anyCrossing());

        // endHold() is what turns limit signals back on (the caller re-arms the drive around it).
        cell.endHold();
        quickly().untilAsserted(() -> {
            cell.update(batch(152f));
            verify(sink, atLeastOnce()).accept(crossing(Limit.UPPER));
        });
    }

    // ---- Loop crash escalation (MeasurementLoop's catch-all around the measurement loop) ----

    @Test
    void loopCrashMidRunSafeStopsThenReportsAWatchdogFailureNeverABareStopSignal() {
        // Crash the loop body at its last statement, the limit decision: every exception on the
        // measurement thread takes the same catch, and the spy is the cheapest seam to throw from.
        doThrow(new RuntimeException("boom mid-run")).when(sink).accept(crossing(Limit.UPPER));
        startLoop();

        cell.update(batch(150f)); // above the upper limit, so the crashing signal is reached

        quickly().until(() -> !cell.loopThread().isAlive());

        InOrder escalation = inOrder(motorSafety, sink);
        escalation.verify(motorSafety).safeStop(contains("load cell thread failed"));
        escalation.verify(sink).accept(watchdogFailure("boom mid-run"));
        // A crashed watchdog is a FAULT, not a finished run or a sensor loss: one event of its own
        // type and no hold to resume from.
        verify(sink, times(1)).accept(any(RunEvent.WatchdogFailed.class));
        verify(lossListener, never()).onSensorLoss(anyString(), anyFloat());
        verify(gapRecorder, never()).beginGap(anyString(), anyFloat());
    }

    @Test
    void aSinkThatRefusesTheFailureReportLeavesTheMotorStoppedAndTheLoopEnded() {
        // Every event refused: the crossing that crashes the loop and the failure report after it.
        doThrow(new RuntimeException("boom mid-run")).when(sink).accept(crossing(Limit.UPPER));
        doThrow(new RuntimeException("loop down")).when(sink).accept(watchdogFailure("boom mid-run"));
        startLoop();

        cell.update(batch(150f));

        // The de-energize came first and does not depend on the report reaching anyone.
        quickly().until(() -> !cell.loopThread().isAlive());
        verify(motorSafety).safeStop(contains("load cell thread failed"));
        verify(sink).accept(watchdogFailure("boom mid-run"));
    }

    @Test
    void aForceAboveTheCeilingStopsTheMotorAndEndsTheRunAsAFault() {
        withCeiling(500.0);
        startLoop();

        cell.update(batch(50f, 600f));

        quickly().untilAsserted(() -> verify(sink).accept(ceilingTrip()));
        verify(motorSafety).safeStop(contains("exceeded the upper shut-off"));
        // A hard trip, not a sensor incident: nothing to reconnect, so the ceiling never opens a
        // hold or a gap. Matched on the reason, not isHolding(): the test stream goes silent after
        // this one batch, so the no-data watchdog legitimately parks the loop 250 ms later, and an
        // unqualified check raced it.
        verify(gapRecorder, never()).beginGap(contains("exceeded"), anyFloat());
        verify(lossListener, never()).onSensorLoss(contains("exceeded"), anyFloat());
    }

    @Test
    void withoutACeilingAHighForceIsOnlyALimitCrossing() {
        startLoop();

        cell.update(batch(50f, 600f));

        quickly().untilAsserted(() -> verify(sink, atLeastOnce()).accept(crossing(Limit.UPPER)));
        verify(sink, never()).accept(any(RunEvent.CeilingTripped.class));
        verify(sink, never()).accept(any(RunEvent.WatchdogFailed.class));
    }

    @Test
    void anErrorInTheLoopStillStopsTheMotor() {
        // An Error, not an Exception: catch (Exception) let this end the watchdog with the motor running.
        doThrow(new OutOfMemoryError("simulated")).when(context).getUpperLimit();
        startLoop();

        cell.update(batch(50f));

        // Both inside the wait: the loop calls the listener after safeStop returns, so verifying it
        // the moment safeStop is seen races the measurement thread.
        quickly().untilAsserted(() -> {
            verify(motorSafety).safeStop(contains("load cell thread failed"));
            verify(sink).accept(watchdogFailure("load cell thread failed"));
        });
    }

    @Test
    void theWatchdogReportsArmedOnlyOnceItsOwnLoopHasSeenASample() throws Exception {
        startLoop();
        assertThat(cell.awaitFirstSample(100)).isFalse();

        cell.update(batch(5f));

        assertThat(cell.awaitFirstSample(2_000)).isTrue();
    }

    // ---- Inbound queue overflow is a safety event (OQ-64 step 10) ----

    @Test
    void anInboundOverflowDeEnergizesOnTheReaderThreadThenEndsTheRunAsAWatchdogFailure() throws Exception {
        // The consumer stalls inside its first batch: the loop reads the watchdog clock right after
        // taking a batch off the queue, and this clock holds it there until released.
        java.util.concurrent.CountDownLatch stalled = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.atomic.AtomicBoolean first = new java.util.concurrent.atomic.AtomicBoolean(true);
        cell.useWatchdogClock(() -> {
            if (first.getAndSet(false)) {
                stalled.countDown();
                try {
                    release.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            return System.nanoTime();
        });
        java.util.concurrent.atomic.AtomicReference<Thread> stoppedOn = new java.util.concurrent.atomic.AtomicReference<>();
        when(motorSafety.safeStop(contains("batches behind"))).thenAnswer(invocation -> {
            stoppedOn.set(Thread.currentThread());
            return new SafeStopResult(SafeStopResult.Tier.EXISTING_HANDLE, true, true, true, 0, "stubbed stop");
        });
        startLoop();
        try {
            cell.update(batch(1f));
            assertThat(stalled.await(2, TimeUnit.SECONDS)).as("the loop took its first batch").isTrue();

            // The queue's whole capacity is accepted: a backlog alone is not an incident.
            for (int i = 0; i < MeasurementLoop.QUEUE_CAPACITY_BATCHES; i++) {
                cell.update(batch(1f + (i % 7) * 0.01f));
            }
            verify(motorSafety, never()).safeStop(anyString());
            verify(sink, never()).accept(any(RunEvent.WatchdogFailed.class));

            // The first refused batch: this thread is the driver's reader, and it stops the motor
            // before the run hears of it, as a dead loop does (I3).
            cell.update(batch(2f));

            InOrder escalation = inOrder(motorSafety, sink);
            escalation.verify(motorSafety).safeStop(contains("batches behind"));
            escalation.verify(sink).accept(watchdogFailure(
                    "fell " + MeasurementLoop.QUEUE_CAPACITY_BATCHES + " batches behind"));
            assertThat(stoppedOn.get()).isSameAs(Thread.currentThread());

            // Once per run: the run is over, later overflow is only counted.
            cell.update(batch(3f));
            cell.update(batch(4f));
            verify(motorSafety, times(1)).safeStop(contains("batches behind"));
            verify(sink, times(1)).accept(any(RunEvent.WatchdogFailed.class));
            verify(lossListener, never()).onSensorLoss(anyString(), anyFloat());
        } finally {
            release.countDown();
        }
    }

    // ---- The loop blocks in poll(timeout); the timeout is the no-data watchdog (step 4, A-12) ----

    /** A watchdog clock the test moves by hand, counting reads so a test can wait for the loop. */
    private static final class ManualClock implements java.util.function.LongSupplier {
        private final java.util.concurrent.atomic.AtomicLong now = new java.util.concurrent.atomic.AtomicLong(1_000_000_000L);
        private final java.util.concurrent.atomic.AtomicLong reads = new java.util.concurrent.atomic.AtomicLong();

        @Override
        public long getAsLong() {
            reads.incrementAndGet();
            return now.get();
        }

        void advanceMillis(long millis) {
            now.addAndGet(TimeUnit.MILLISECONDS.toNanos(millis));
        }

        void advanceNanos(long nanos) {
            now.addAndGet(nanos);
        }

        /** Waits until the loop has read the clock {@code count} more times, i.e. re-evaluated it. */
        void awaitReads(int count) {
            long target = reads.get() + count;
            quickly().until(() -> reads.get() >= target);
        }
    }

    @Test
    void theNoDataTripCountsFromTheBatchsArrivalEvenWhenProcessingItOutlastsTheTimeout() throws Exception {
        ManualClock clock = new ManualClock();
        cell.useWatchdogClock(clock);
        java.util.concurrent.CountDownLatch processing = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch finishProcessing = new java.util.concurrent.CountDownLatch(1);
        // The batch's limit crossing is the slow step: the loop is inside its processing here.
        org.mockito.Mockito.doAnswer(invocation -> {
            processing.countDown();
            assertThat(finishProcessing.await(5, TimeUnit.SECONDS)).isTrue();
            return null;
        }).when(sink).accept(crossing(Limit.UPPER));
        startLoop();

        cell.update(batch(150f));
        assertThat(processing.await(2, TimeUnit.SECONDS)).isTrue();
        // Processing the batch takes longer than the whole no-data timeout.
        clock.advanceMillis(300);
        finishProcessing.countDown();

        // Counted from when the batch was taken, the silence is already past 250 ms, so the next
        // poll has no time left and trips; counted from the end of processing it never would, on
        // a clock that no longer moves.
        quickly().untilAsserted(() -> verify(lossListener).onSensorLoss(contains("no measurement"), eq(150f)));
        InOrder escalation = inOrder(motorSafety, gapRecorder, lossListener);
        escalation.verify(motorSafety).safeStop(contains("no measurement"));
        escalation.verify(gapRecorder).beginGap(contains("no measurement"), eq(150f));
        escalation.verify(lossListener).onSensorLoss(anyString(), anyFloat());
    }

    @Test
    void theNoDataTripFiresPastTheTimeoutAndNotAtIt() {
        ManualClock clock = new ManualClock();
        cell.useWatchdogClock(clock);
        startLoop();

        cell.update(batch(5f));
        quickly().until(() -> cell.getLastForce() == 5f);

        // Exactly 250 ms of silence is not yet a trip; the loop re-evaluates it several times.
        clock.advanceMillis(250);
        clock.awaitReads(5);
        assertThat(cell.isHolding()).isFalse();
        verify(lossListener, never()).onSensorLoss(anyString(), anyFloat());

        clock.advanceNanos(1);
        quickly().until(cell::isHolding);
        awaitLossEvents(1);
        verify(lossListener).onSensorLoss(contains("no measurement for more than 250 ms"), eq(5f));
    }

    @Test
    void aForceThatStaysAboveTheLimitAcrossDrainsIsSentOnce() {
        startLoop();

        // Each update is its own drain once the previous one was processed.
        for (float force : new float[]{150f, 151f, 152f, 153f}) {
            cell.update(batch(force));
            quickly().until(() -> cell.getLastForce() == force);
        }

        // The edge suppresses the repeats: the loop before step 4 signalled on every drain above
        // the limit.
        verify(sink, times(1)).accept(crossing(Limit.UPPER));
        verify(sink, times(1)).accept(anyCrossing());
    }

    /**
     * Step 5's handover from step 4: a timed turn enters the run through the crossings' repeat
     * filter. A timed PULL re-arms the upper edge, so a force still above the upper limit is a new
     * crossing; without it a time-cyclic run loses its upper-limit reversal after the first timed
     * turn. A timed turn repeating the last edge is dropped, as the shared int filter dropped it.
     */
    @Test
    void aTimedTurnReArmsTheOppositeCrossingAndARepeatOfTheLastTurnIsDropped() {
        startLoop();
        cell.update(batch(150f));
        quickly().untilAsserted(() -> verify(sink).accept(crossing(Limit.UPPER)));

        RunEvent.TimedTurn timedPull = new RunEvent.TimedTurn(ch.rupfizupfi.deck.testrunner.run.Direction.PULL);
        cell.timedTurns().accept(timedPull);
        cell.timedTurns().accept(timedPull);
        verify(sink, times(1)).accept(timedPull);

        cell.update(batch(151f));
        quickly().untilAsserted(() -> verify(sink, times(2)).accept(crossing(Limit.UPPER)));
        // The second upper crossing makes RELEASE the last turn again, so the next timed pull is let in.
        cell.timedTurns().accept(timedPull);
        verify(sink, times(2)).accept(timedPull);
    }

    @Test
    void aTimedTurnAgreeingWithTheLastCrossingIsDropped() {
        startLoop();
        cell.update(batch(150f));
        quickly().untilAsserted(() -> verify(sink).accept(crossing(Limit.UPPER)));

        cell.timedTurns().accept(new RunEvent.TimedTurn(ch.rupfizupfi.deck.testrunner.run.Direction.RELEASE));

        verify(sink, never()).accept(any(RunEvent.TimedTurn.class));
    }

    @Test
    void aBatchWakesTheLoopWithinFiveMilliseconds() throws Exception {
        java.util.concurrent.BlockingQueue<Long> detected = new java.util.concurrent.LinkedBlockingQueue<>();
        org.mockito.Mockito.doAnswer(invocation -> {
            detected.add(System.nanoTime());
            return null;
        }).when(sink).accept(anyCrossing());
        startLoop();

        // Alternating crossings, so each batch is a new edge the detector sends.
        List<Long> latencies = new ArrayList<>();
        for (int i = 0; i < 21; i++) {
            float force = i % 2 == 0 ? 150f : -150f;
            long offered = System.nanoTime();
            cell.update(batch(force));
            Long seen = detected.poll(2, TimeUnit.SECONDS);
            assertThat(seen).as("crossing %d detected", i).isNotNull();
            latencies.add(seen - offered);
        }

        // The median, so one descheduled test thread cannot decide it; the old loop parked 20 ms
        // on an empty buffer, so its median sat near 10 ms.
        latencies.sort(null);
        assertThat(TimeUnit.NANOSECONDS.toMicros(latencies.get(latencies.size() / 2)))
                .as("median update() to detector latency, microseconds").isLessThan(5_000);
    }

    @Test
    void aCeilingTripStopsTheMotorOnTheMeasurementThreadBeforeTheRunIsTold() {
        withCeiling(500.0);
        java.util.concurrent.atomic.AtomicReference<Thread> stoppedOn = new java.util.concurrent.atomic.AtomicReference<>();
        when(motorSafety.safeStop(contains("exceeded the upper shut-off"))).thenAnswer(invocation -> {
            stoppedOn.set(Thread.currentThread());
            return new SafeStopResult(SafeStopResult.Tier.EXISTING_HANDLE, true, true, true, 0, "stubbed stop");
        });
        startLoop();

        // 600 N is above the 100 N upper limit as well: the ceiling must win and no crossing leave.
        cell.update(batch(50f, 600f));

        quickly().untilAsserted(() -> verify(sink).accept(ceilingTrip()));
        InOrder escalation = inOrder(motorSafety, sink);
        escalation.verify(motorSafety).safeStop(contains("exceeded the upper shut-off"));
        escalation.verify(sink).accept(any(RunEvent.CeilingTripped.class));
        assertThat(stoppedOn.get()).isSameAs(cell.loopThread());
        verify(sink, never()).accept(crossing(Limit.UPPER));
    }

    @Test
    void aSensorLossDeEnergizesOnTheMeasurementThreadFirst() {
        java.util.concurrent.atomic.AtomicReference<Thread> stoppedOn = new java.util.concurrent.atomic.AtomicReference<>();
        when(motorSafety.safeStop(contains("implausible"))).thenAnswer(invocation -> {
            stoppedOn.set(Thread.currentThread());
            return new SafeStopResult(SafeStopResult.Tier.EXISTING_HANDLE, true, true, true, 0, "stubbed stop");
        });
        startLoop();

        // Above the upper limit too: a drain that trips the sensor loss sends no crossing.
        cell.update(batch(150f, Float.NaN, 150f, Float.NaN, Float.NaN));
        quickly().until(cell::isHolding);

        assertThat(stoppedOn.get()).isSameAs(cell.loopThread());
        verify(sink, never()).accept(anyCrossing());
    }

    // ---- The CSV is written off the measurement thread (step 9, A-07) ----

    /** A disk stuck inside a write until released; the trace writer thread is the one stuck. */
    private static final class StalledDisk extends java.io.Writer {
        final java.util.concurrent.CountDownLatch stuck = new java.util.concurrent.CountDownLatch(1);
        final java.util.concurrent.CountDownLatch released = new java.util.concurrent.CountDownLatch(1);

        @Override
        public void write(char[] buffer, int offset, int length) throws java.io.IOException {
            stuck.countDown();
            try {
                released.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new java.io.IOException("interrupted", e);
            }
        }

        @Override
        public void flush() throws java.io.IOException {
            write(new char[0], 0, 0);
        }

        @Override
        public void close() {
        }
    }

    /** A plausible, never bit-identical ramp inside the limits, long enough to overflow 8 KiB of rows. */
    private static List<Measurement> quietRamp(int count) {
        List<Measurement> measurements = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            measurements.add(new Measurement(10f + i * 0.001f, System.currentTimeMillis()));
        }
        return measurements;
    }

    @Test
    void aStalledDiskDelaysNoLimitCrossing() throws Exception {
        StalledDisk disk = new StalledDisk();
        cell.useTraceOutput(path -> disk);
        startLoop();
        try {
            cell.update(quietRamp(1000));
            assertThat(disk.stuck.await(2, TimeUnit.SECONDS)).as("trace writer stuck in the disk").isTrue();

            // The disk stays stuck for the whole check: the crossing must not wait for it.
            long offered = System.nanoTime();
            cell.update(batch(150f));
            quickly().untilAsserted(() -> verify(sink).accept(crossing(Limit.UPPER)));
            long tookMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - offered);

            assertThat(disk.released.getCount()).as("disk still stalled").isEqualTo(1);
            assertThat(tookMs).as("update() to crossing with the disk stalled, ms").isLessThan(500);
            verify(sink, never()).accept(any(RunEvent.PersistenceFailed.class));
        } finally {
            disk.released.countDown();
        }
    }

    @Test
    void theLossPathFlushesTheTailBeforeTheGapRecordEvenWithTheWriterBehind() throws Exception {
        startLoop();
        // Several batches queued before the trip: the trace writer is likely still behind when the
        // loss path runs, and its bounded flush must have put every row on disk before the gap.
        cell.update(quietRamp(400));
        cell.update(quietRamp(400));
        org.mockito.Mockito.doAnswer(invocation -> {
            assertThat(Files.readAllLines(csvPath)).as("rows on disk when the gap opens").hasSize(805);
            return null;
        }).when(gapRecorder).beginGap(anyString(), anyFloat());
        cell.update(batch(Float.NaN, 1f, Float.NaN, 2f, Float.NaN));
        quickly().until(cell::isHolding);
        awaitLossEvents(1);

        InOrder escalation = inOrder(motorSafety, gapRecorder, lossListener);
        escalation.verify(motorSafety).safeStop(contains("implausible"));
        escalation.verify(gapRecorder).beginGap(anyString(), anyFloat());
        escalation.verify(lossListener).onSensorLoss(anyString(), anyFloat());
    }

    @Test
    void aFailingDiskRaisesAPersistenceFailureAndStopsNothing() {
        cell.useTraceOutput(path -> new java.io.Writer() {
            @Override
            public void write(char[] buffer, int offset, int length) throws java.io.IOException {
                throw new java.io.IOException("disk full");
            }

            @Override
            public void flush() throws java.io.IOException {
                throw new java.io.IOException("disk full");
            }

            @Override
            public void close() {
            }
        });
        startLoop();

        cell.update(quietRamp(1000));

        quickly().untilAsserted(() -> verify(sink).accept(argThat(event ->
                event instanceof RunEvent.PersistenceFailed failed && failed.detail().contains("disk full"))));
        // Not a safety event: before step 9 a write failure killed the watchdog and stopped the
        // motor from the measurement thread; now the detectors keep running and the loop ends the run.
        verify(motorSafety, never()).safeStop(contains("load cell thread failed"));
        verify(sink, never()).accept(any(RunEvent.WatchdogFailed.class));
        cell.update(batch(150f));
        quickly().untilAsserted(() -> verify(sink).accept(crossing(Limit.UPPER)));
        verify(sink, times(1)).accept(any(RunEvent.PersistenceFailed.class));
    }

    // ---- The sample-interval detector: a feed slower than the bound but never silent ----

    /** Distinct forces across every drain, so each wait below matches exactly one drain. */
    private float nextForce = 10f;

    private float[] distinctForces(int count) {
        float[] forces = new float[count];
        for (int i = 0; i < count; i++) {
            nextForce += 0.01f;
            forces[i] = nextForce;
        }
        return forces;
    }

    /**
     * Feeds one drain at the clock's current time and waits until the loop has taken it. The drain's
     * arrival, and so its vote, is read before its first sample is, so the clock may move on.
     */
    private void feedDrain(int samples) {
        float[] forces = distinctForces(samples);
        float last = forces[forces.length - 1];
        cell.update(batch(forces));
        quickly().until(() -> cell.getLastForce() == last);
    }

    /** Four samples per 20 ms reader tick: the simulated bench's 5 ms rate. */
    private void feedHealthy(ManualClock clock, int drains) {
        for (int i = 0; i < drains; i++) {
            clock.advanceMillis(20);
            feedDrain(4);
        }
    }

    /**
     * The loop reads the clock again only in its next poll, after it has decided the last drain, and
     * never while holding, so a trip there times this wait out.
     */
    private void assertNoSensorLoss(ManualClock clock) {
        clock.awaitReads(1);
        assertThat(cell.isHolding()).isFalse();
        verify(lossListener, never()).onSensorLoss(anyString(), anyFloat());
        verify(motorSafety, never()).safeStop(anyString());
    }

    /** One distinct sample every 200 ms: under the 250 ms no-data timeout, never 100 identical. */
    private void tripByTrickle(ManualClock clock) {
        feedDrain(1);
        for (int i = 0; i < 2; i++) {
            clock.advanceMillis(200);
            feedDrain(1);
        }
        clock.advanceMillis(200);
        cell.update(batch(distinctForces(1)));
        quickly().until(cell::isHolding);
        awaitLossEvents(1);
    }

    /**
     * The hold loop applies markSensorRecovered()'s reset, which clears the queue, within one 20 ms
     * park; a batch fed before that is legitimately dropped, and a re-feed would add drains.
     */
    private void recoverAndAwaitTheReset() throws InterruptedException {
        cell.markSensorRecovered();
        Thread.sleep(100);
    }

    @Test
    void aTrickleUnderTheNoDataTimeoutTripsTheSampleIntervalBoundWithinThreeDrains() {
        ManualClock clock = new ManualClock();
        cell.useWatchdogClock(clock);
        startLoop();

        // The first drain has nothing to be timed against; the next two are one vote each.
        feedDrain(1);
        clock.advanceMillis(200);
        feedDrain(1);
        clock.advanceMillis(200);
        feedDrain(1);
        // Bounded, not eager: two sparse drains of five are not yet a loss.
        assertNoSensorLoss(clock);

        // The third sparse drain, 600 ms of stream after the first: the loss, through sensorLost().
        clock.advanceMillis(200);
        float last = distinctForces(1)[0];
        cell.update(batch(last));
        quickly().until(cell::isHolding);
        awaitLossEvents(1);

        verify(lossListener).onSensorLoss(contains(
                "load cell delivered 1 sample(s) 200 ms after the previous drain (200.0 ms per sample), "
                        + "slower than the 100 ms bound in 3 of the last 5 drains"), eq(last));
        InOrder escalation = inOrder(motorSafety, gapRecorder, lossListener);
        escalation.verify(motorSafety).safeStop(contains("slower than the 100 ms bound"));
        escalation.verify(gapRecorder).beginGap(contains("slower than the 100 ms bound"), eq(last));
        escalation.verify(lossListener).onSensorLoss(anyString(), anyFloat());
        assertThat(cell.sparseDrainCount()).isEqualTo(3);
    }

    @Test
    void aHealthyFeedAtTheSimulatorRateNeverTripsTheSampleIntervalBound() {
        ManualClock clock = new ManualClock();
        cell.useWatchdogClock(clock);
        startLoop();

        feedDrain(4);
        feedHealthy(clock, 150); // 3 s of stream

        assertNoSensorLoss(clock);
        assertThat(cell.sparseDrainCount()).isZero();
    }

    @Test
    void anIsolatedHoleUnderTheNoDataTimeoutIsOneVoteNotATrip() {
        ManualClock clock = new ManualClock();
        cell.useWatchdogClock(clock);
        startLoop();

        feedDrain(4);
        feedHealthy(clock, 6);
        // A hole the driver absorbed: 240 ms, then a single sample. Two of them within five drains
        // are two votes, one short of the trip.
        clock.advanceMillis(240);
        feedDrain(1);
        feedHealthy(clock, 2);
        clock.advanceMillis(240);
        feedDrain(1);
        // The votes age out like plausibility votes, so a later hole starts from zero again.
        feedHealthy(clock, 6);
        clock.advanceMillis(240);
        feedDrain(1);
        feedHealthy(clock, 6);

        assertNoSensorLoss(clock);
        // Counted although none tripped: the evidence a bench run below the trip leaves.
        assertThat(cell.sparseDrainCount()).isEqualTo(3);
    }

    @Test
    void aBurstyBusIsNotATrickle() {
        ManualClock clock = new ManualClock();
        cell.useWatchdogClock(clock);
        startLoop();

        // Ten samples every 240 ms is 24 ms per sample: the density is fine, and the no-data timeout
        // already bounds how long a decision waits. The bound is on density, not latency.
        feedDrain(10);
        for (int i = 0; i < 9; i++) {
            clock.advanceMillis(240);
            feedDrain(10);
        }

        assertNoSensorLoss(clock);
        assertThat(cell.sparseDrainCount()).isZero();
    }

    @Test
    void theFirstDrainAfterAReconnectCastsNoVoteAndTheVotesAreCleared() throws Exception {
        useGateWindow(20);
        ManualClock clock = new ManualClock();
        cell.useWatchdogClock(clock);
        ManualClock gateClock = new ManualClock();
        cell.useGateClock(gateClock);
        startLoop();
        tripByTrickle(clock);
        float lastKnown = cell.getLastForce();

        recoverAndAwaitTheReset();
        CompletableFuture<MeasurementLoop.GateResult> gate = cell.beginRecoveryGate(lastKnown, 1000.0);

        // Five seconds after the dead session's last drain. Had it voted, or had the trip's three
        // votes survived the reset, the two sparse drains after it would be a second loss.
        clock.advanceMillis(5_000);
        feedDrain(4);
        clock.advanceMillis(200);
        feedDrain(1);
        clock.advanceMillis(200);
        feedDrain(1);
        clock.awaitReads(1);
        verify(lossListener, times(1)).onSensorLoss(anyString(), anyFloat());

        // A healthy stream then passes the gate: the next drain starts its run, the one 20 ms
        // later completes it.
        feedHealthy(clock, 1);
        gateClock.advanceMillis(20);
        feedHealthy(clock, 1);
        assertThat(gate.get(2, TimeUnit.SECONDS).passed()).isTrue();
        verify(motorSafety, times(1)).safeStop(anyString());
        verify(lossListener, times(1)).onSensorLoss(anyString(), anyFloat());
        // The count is the run's, not the stream's: the trip's three and the two after the reconnect.
        assertThat(cell.sparseDrainCount()).isEqualTo(5);
    }

    @Test
    void aTricklingReconnectedStreamNeverPassesTheGate() throws Exception {
        useGateWindow(20);
        ManualClock clock = new ManualClock();
        cell.useWatchdogClock(clock);
        ManualClock gateClock = new ManualClock();
        cell.useGateClock(gateClock);
        startLoop();
        tripSensorLoss();

        recoverAndAwaitTheReset();
        CompletableFuture<MeasurementLoop.GateResult> gate = cell.beginRecoveryGate(10f, 1000.0);

        // The gate's timer runs on its own clock, moved past the 20 ms window before each drain: on
        // time alone two plausible samples would pass it, so only the sparse drain may keep it open.
        feedDrain(1);
        for (int i = 0; i < 2; i++) {
            gateClock.advanceMillis(30);
            clock.advanceMillis(200);
            feedDrain(1);
            clock.awaitReads(1);
            assertThat(gate.isDone()).as("gate after sparse drain %d", i + 1).isFalse();
        }

        // The third sparse drain is a second loss, which fails the gate at once.
        gateClock.advanceMillis(30);
        clock.advanceMillis(200);
        cell.update(batch(distinctForces(1)));
        MeasurementLoop.GateResult result = gate.get(2, TimeUnit.SECONDS);

        assertThat(result.passed()).isFalse();
        assertThat(result.detail()).contains("lost again during the gate").contains("slower than the 100 ms bound");
        assertThat(cell.isHolding()).isTrue();
        verify(motorSafety, times(2)).safeStop(anyString());
        awaitLossEvents(2);
    }

    @Test
    void aReconnectedStreamInsideTheRateFloorStillNeedsTheGatesSampleCount() throws Exception {
        // The floor raised to 240 ms, so a feed one sample per 200 ms casts no sparse vote and only
        // the sample count can keep the gate closed; the trip itself is a 245 ms trickle.
        RecoveryProperties tuned = new RecoveryProperties();
        tuned.setMaxSampleIntervalMillis(240);
        tuned.setPlausibilityGateMinSamples(4);
        cell = threadWith(tuned.snapshot());
        ManualClock clock = new ManualClock();
        cell.useWatchdogClock(clock);
        ManualClock gateClock = new ManualClock();
        cell.useGateClock(gateClock);
        startLoop();

        feedDrain(1);
        for (int i = 0; i < 2; i++) {
            clock.advanceMillis(245);
            feedDrain(1);
        }
        clock.advanceMillis(245);
        cell.update(batch(distinctForces(1)));
        quickly().until(cell::isHolding);
        awaitLossEvents(1);
        verify(lossListener).onSensorLoss(contains("slower than the 240 ms bound"), anyFloat());
        float lastKnown = cell.getLastForce();

        recoverAndAwaitTheReset();
        CompletableFuture<MeasurementLoop.GateResult> gate = cell.beginRecoveryGate(lastKnown, 1000.0);

        // From the second sample on the 100 ms window is met; only the count is not.
        feedDrain(1);
        for (int i = 2; i <= 3; i++) {
            clock.advanceMillis(200);
            gateClock.advanceMillis(200);
            feedDrain(1);
            clock.awaitReads(1);
            assertThat(gate.isDone()).as("gate after sample %d", i).isFalse();
        }

        clock.advanceMillis(200);
        gateClock.advanceMillis(200);
        feedDrain(1);
        MeasurementLoop.GateResult result = gate.get(2, TimeUnit.SECONDS);
        assertThat(result.passed()).isTrue();
        assertThat(result.detail()).contains("over 4 samples");
        verify(lossListener, times(1)).onSensorLoss(anyString(), anyFloat());
    }

    @Test
    void aHealthyReconnectAtTheSimulatorRatePassesTheDefaultGateInsideItsDeadline() throws Exception {
        // The defaults: 100 ms and 10 samples, deadline 2 x 100 ms + 1 s.
        ManualClock clock = new ManualClock();
        cell.useWatchdogClock(clock);
        ManualClock gateClock = new ManualClock();
        cell.useGateClock(gateClock);
        startLoop();
        tripByTrickle(clock);
        float lastKnown = cell.getLastForce();

        recoverAndAwaitTheReset();
        CompletableFuture<MeasurementLoop.GateResult> gate = cell.beginRecoveryGate(lastKnown, 1000.0);

        // Four samples per 20 ms drain: the count is met by the third drain, the window by the first
        // sample of the sixth, which passes the gate on the 21st sample.
        feedDrain(4);
        for (int elapsed = 20; elapsed <= 80; elapsed += 20) {
            gateClock.advanceMillis(20);
            feedHealthy(clock, 1);
            clock.awaitReads(1);
            assertThat(gate.isDone()).as("gate at %d ms", elapsed).isFalse();
        }
        gateClock.advanceMillis(20);
        feedHealthy(clock, 1);

        MeasurementLoop.GateResult result = gate.get(2, TimeUnit.SECONDS);
        assertThat(result.passed()).isTrue();
        assertThat(result.detail()).contains("stayed plausible for 100 ms over 21 samples");
        verify(lossListener, times(1)).onSensorLoss(anyString(), anyFloat());
    }

    /** One bit-identical sample per drain, the watchdog clock moved by {@code intervalMillis} each. */
    private void feedOneHeldValuePerDrain(ManualClock clock, long intervalMillis, int drains) {
        for (int i = 0; i < drains; i++) {
            if (i > 0) {
                clock.advanceMillis(intervalMillis);
            }
            cell.update(batch(42.5f));
            if (i < drains - 1) {
                // The drain's arrival, then the next poll's timeout: the loop has processed it. Not
                // after the last, which trips: a parked loop reads no clock.
                clock.awaitReads(2);
            }
        }
    }

    @Test
    void theRunningLoopTripsASlowFrozenFeedOnTheTimeBound() {
        // 80 ms per drain: inside the 100 ms rate floor and the 250 ms no-data timeout, so only the
        // frozen detector's time bound can trip, on the 13th repeat.
        ManualClock clock = new ManualClock();
        cell.useWatchdogClock(clock);
        startLoop();

        feedOneHeldValuePerDrain(clock, 80, 14);

        quickly().untilAsserted(() -> verify(lossListener).onSensorLoss(
                contains("force frozen at 42.5 N for 13 consecutive bit-identical samples over 1040 ms"),
                anyFloat()));
        verify(motorSafety).safeStop(contains("force frozen at"));
        verify(lossListener, never()).onSensorLoss(contains("slower than"), anyFloat());
    }

    @Test
    void theRunningLoopTripsAFiveHertzFrozenFeedOnTheTimeBound() {
        // The rate floor raised to 240 ms so 5 Hz casts no sparse vote: five repeats are 1000 ms.
        RecoveryProperties tuned = new RecoveryProperties();
        tuned.setMaxSampleIntervalMillis(240);
        cell = threadWith(tuned.snapshot());
        ManualClock clock = new ManualClock();
        cell.useWatchdogClock(clock);
        startLoop();

        feedOneHeldValuePerDrain(clock, 200, 6);

        quickly().untilAsserted(() -> verify(lossListener).onSensorLoss(
                contains("for 5 consecutive bit-identical samples over 1000 ms"), anyFloat()));
        verify(motorSafety).safeStop(contains("force frozen at"));
        verify(lossListener, never()).onSensorLoss(contains("slower than"), anyFloat());
    }

    @Test
    void aLoopStallIsNotAHeldValueWhenItsBacklogRepeatsTheLastSample() throws Exception {
        ManualClock clock = new ManualClock();
        cell.useWatchdogClock(clock);
        java.util.concurrent.CountDownLatch processing = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch finishProcessing = new java.util.concurrent.CountDownLatch(1);
        org.mockito.Mockito.doAnswer(invocation -> {
            processing.countDown();
            assertThat(finishProcessing.await(5, TimeUnit.SECONDS)).isTrue();
            return null;
        }).when(sink).accept(crossing(Limit.UPPER));
        startLoop();

        // The upper limit is 100 N, so this drain's crossing is the slow step.
        cell.update(batch(149.9f, 150f));
        assertThat(processing.await(2, TimeUnit.SECONDS)).isTrue();
        // The loop stalls past the frozen bound while the cell keeps delivering at 5 ms, and the
        // backlog's first sample repeats the value the stalled drain ended on.
        clock.advanceMillis(1_200);
        float[] backlog = new float[240];
        backlog[0] = 150f;
        for (int i = 1; i < backlog.length; i++) {
            backlog[i] = 150f - i * 0.01f;
        }
        cell.update(batch(backlog));
        finishProcessing.countDown();

        float last = backlog[backlog.length - 1];
        quickly().until(() -> cell.getLastForce() == last);
        clock.awaitReads(1);
        verify(lossListener, never()).onSensorLoss(anyString(), anyFloat());
        verify(motorSafety, never()).safeStop(anyString());
    }
}
