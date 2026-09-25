package ch.rupfizupfi.deck.testrunner;

import ch.rupfizupfi.deck.device.api.Measurement;
import ch.rupfizupfi.deck.device.loadcell.LoadCellDevice;
import ch.rupfizupfi.deck.filesystem.CSVStoreService;
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
import static org.mockito.ArgumentMatchers.anyFloat;
import static org.mockito.ArgumentMatchers.anyInt;
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

class LoadCellThreadTest {

    @TempDir
    Path tempDir;

    private TestContext context;
    private LoadCellDevice loadCellDevice;
    private MotorSafetyController motorSafety;
    private SensorLossListener lossListener;
    private GapRecorder gapRecorder;
    private Path csvPath;
    private LoadCellThread cell;
    private boolean started;

    @BeforeEach
    void buildThread() {
        // Context limits double as the seed of the measured min/max envelope.
        context = spy(new TestContext(1L, 100.0, -100.0));
        loadCellDevice = mock(LoadCellDevice.class);
        motorSafety = mock(MotorSafetyController.class);
        lossListener = mock(SensorLossListener.class);
        gapRecorder = mock(GapRecorder.class);
        when(motorSafety.safeStop(anyString())).thenReturn(new SafeStopResult(
                SafeStopResult.Tier.EXISTING_HANDLE, true, true, true, 0, "stubbed stop"));
        csvPath = tempDir.resolve("run_force.csv");
        cell = threadWith(new RecoveryProperties().snapshot());
    }

    private LoadCellThread threadWith(RecoveryGates gates) {
        return new LoadCellThread(context, loadCellDevice,
                new CSVStoreService.TestRunFiles(csvPath.toString(),
                        tempDir.resolve("run_gaps.json").toString()),
                motorSafety, lossListener, gates, gapRecorder);
    }

    /**
     * Rebuilds the thread around a gate window of its own: the knobs are a snapshot taken at
     * construction, so a scenario that needs different ones needs a new thread. Call before start().
     */
    private void useGateWindow(long plausibilityGateMillis) {
        RecoveryProperties tuned = new RecoveryProperties();
        tuned.setPlausibilityGateMillis(plausibilityGateMillis);
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

        // Pre-fix, NaN-absorbing Math.min/max pinned both bounds at NaN forever, which silently
        // disabled CyclicTest's PULL/RELEASE limit derivation.
        assertThat(cell.getMaxValue()).isEqualTo(150f);
        assertThat(cell.getMinValue()).isEqualTo(-150f);
    }

    @Test
    void fractureSizedDropNeverVotesImplausibleButAnEqualRiseDoes() {
        // A fast DROP is a specimen breaking - the event the machine exists to measure. If the rise
        // check were Math.abs(force - previous), every drop below would vote and the 4th sample
        // would already trip 3-of-5; only the rises may vote.
        assertThat(cell.inspectSample(200_000f)).isNull();
        assertThat(cell.inspectSample(10f)).isNull();       // drop: no vote
        assertThat(cell.inspectSample(200_000f)).isNull();  // rise: vote 1
        assertThat(cell.inspectSample(10f)).isNull();       // drop: no vote (abs() would trip here)
        assertThat(cell.inspectSample(200_000f)).isNull();  // rise: vote 2
        assertThat(cell.inspectSample(10f)).isNull();       // drop: no vote
        assertThat(cell.inspectSample(200_000f)).contains("implausible"); // rise: vote 3 of last 5
    }

    @Test
    void frozenDetectorTripsAfterAHundredBitIdenticalRepeats() {
        assertThat(cell.inspectSample(42.5f)).isNull();
        for (int i = 0; i < 99; i++) {
            assertThat(cell.inspectSample(42.5f)).isNull();
        }
        assertThat(cell.inspectSample(42.5f)).contains("frozen");
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
    void sensorLossStopsTheMotorFirstThenRecordsTheGapThenTellsTheListener() throws Exception {
        startLoop();
        // ONE batch: the 3-of-5 window advances per SAMPLE, so a single drain must be able to trip
        // it - advanced per batch, this could never reach three votes.
        cell.update(batch(Float.NaN, 1f, Float.NaN, 2f, Float.NaN));
        quickly().until(cell::isHolding);

        InOrder escalation = inOrder(motorSafety, gapRecorder, lossListener);
        escalation.verify(motorSafety).safeStop(contains("implausible"));
        escalation.verify(gapRecorder).beginGap(anyString(), anyFloat());
        escalation.verify(lossListener).onSensorLoss(anyString(), anyFloat());

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
        verify(lossListener).onSensorLoss(contains("no measurement"), eq(5f));
    }

    @Test
    void intraBatchCrossingFiresEvenWhenTheLastSampleIsBackInside() {
        startLoop();
        // 150 N crosses the 100 N upper limit in the middle of the drain; the batch ends inside
        // the limits, so only a per-sample decision can see the crossing at all.
        cell.update(batch(50f, 150f, 60f));
        quickly().untilAsserted(() -> verify(context).sendSignal(TestContext.RELEASE_SIGNAL));
    }

    @Test
    void aBatchCrossingBothLimitsEmitsOnlyTheEarlierCrossing() {
        startLoop();
        cell.update(batch(-150f, 150f));
        quickly().untilAsserted(() -> verify(context).sendSignal(TestContext.PULL_SIGNAL));

        // A finite marker batch: drains are processed one after the other on one thread, so once
        // the marker is through, the whole first drain has provably been decided.
        cell.update(batch(48f, 47f));
        quickly().until(() -> cell.getLastForce() == 47f);
        verify(context, never()).sendSignal(TestContext.RELEASE_SIGNAL);
        verify(context, times(1)).sendSignal(anyInt());
    }

    @Test
    void aNanInTheMiddleOfAnInsideBatchDecidesNothing() {
        startLoop();
        // One NaN is one plausibility vote of five, two short of a trip, and it decides no limit:
        // both comparisons are false for it, so an unguarded check would read as inside both.
        cell.update(batch(50f, Float.NaN, 49f));
        cell.update(batch(48f, 47f));
        quickly().until(() -> cell.getLastForce() == 47f);
        verify(context, never()).sendSignal(anyInt());
    }

    @Test
    void nonFiniteSamplesNeverDecideTheLimit() throws Exception {
        startLoop();
        // No finite sample in these batches crosses a limit, and the infinities decide nothing, so
        // however the drains merge no signal may leave. The finite fillers keep the 250 ms no-data
        // watchdog fed and hold the plausibility vote below its 3-of-5 trip.
        for (int i = 0; i < 20; i++) {
            cell.update(batch(50f, 49f, 48f, Float.POSITIVE_INFINITY));
            Thread.sleep(5);
        }
        quickly().until(() -> Float.isInfinite(cell.getLastForce()));

        // A finite marker batch: drains are processed one after the other on one thread, so once
        // the marker is through, every drain above has provably been decided.
        cell.update(batch(46f, 45f, 47f));
        quickly().until(() -> cell.getLastForce() == 47f);
        verify(context, never()).sendSignal(anyInt());

        // The guard skips a sample, it does not retire the decision: the next finite crossing fires.
        cell.update(batch(150f));
        quickly().untilAsserted(() -> verify(context).sendSignal(TestContext.RELEASE_SIGNAL));
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
        CompletableFuture<LoadCellThread.GateResult> gate = cell.beginRecoveryGate(100f, 1000.0);

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
        LoadCellThread.GateResult result = gate.get();
        assertThat(result.passed()).isTrue();
        // Drift is measured from the FIRST post-reconnect sample, pass or fail: it is the only
        // number that will ever exist for retuning the drift fraction against a real specimen.
        assertThat(result.firstForce()).isEqualTo(150f);
        assertThat(result.drift()).isEqualTo(50f);
        assertThat(result.driftFraction()).isEqualTo(50f / 1000.0);
    }

    @Test
    void gateTreatsBitIdenticalSamplesAsFrozenNoMatterHowMuchTimePasses() throws Exception {
        useGateWindow(20);
        CompletableFuture<LoadCellThread.GateResult> gate = cell.beginRecoveryGate(50f, 1000.0);

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
        CompletableFuture<LoadCellThread.GateResult> gate = cell.beginRecoveryGate(100f, 1000.0);

        // The first acceptable sample starts the run, and the run has zero length at that instant
        // (both nanoTime reads in scoreGateSample are the same value), so it must not pass yet.
        cell.scoreGateSample(100.5f);
        assertThat(gate).isNotDone();

        Thread.sleep(30);
        cell.scoreGateSample(99.5f);

        assertThat(gate).isDone();
        LoadCellThread.GateResult result = gate.get();
        assertThat(result.passed()).isTrue();
        assertThat(result.detail()).contains("stayed plausible");
    }

    @Test
    void gateKeepsTheSnapshotItWasBuiltWithWhenThePropertiesAreRebound() throws Exception {
        RecoveryProperties properties = new RecoveryProperties();
        properties.setPlausibilityGateMillis(20);
        cell = threadWith(properties.snapshot());

        // A rebind mid-run must not move the gate the run is already being judged by: a zero drift
        // band rejects every sample, and a 600 s window cannot open inside 30 ms.
        properties.setPlausibilityGateMillis(600_000);
        properties.setDriftFraction(0.0);

        CompletableFuture<LoadCellThread.GateResult> gate = cell.beginRecoveryGate(100f, 1000.0);
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
        CompletableFuture<LoadCellThread.GateResult> gate = cell.beginRecoveryGate(50f, 1000.0);

        // Keep-alive feeding with LSB jitter until the verdict - never a fixed number of batches,
        // so a slow drain cannot starve the gate of samples.
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        int i = 0;
        while (!gate.isDone() && System.nanoTime() < deadline) {
            cell.update(batch(50f + (i++ % 3) * 0.1f));
            Thread.sleep(10);
        }

        assertThat(gate).isDone();
        LoadCellThread.GateResult result = gate.get();
        assertThat(result.passed()).isTrue();
        assertThat(result.firstForce()).isCloseTo(50f, within(1f));
        // Passing the gate alone must not resume limit signals; only endHold() may do that.
        verify(context, never()).sendSignal(anyInt());
    }

    @Test
    void gateDeadlineExpiresWhenTheReconnectedStreamDeliversNothing() throws Exception {
        useGateWindow(20); // deadline = 2 * 20 ms + the 1 s slack
        startLoop();
        tripSensorLoss();

        cell.markSensorRecovered();
        CompletableFuture<LoadCellThread.GateResult> gate = cell.beginRecoveryGate(50f, 1000.0);
        // No update() is ever fed: the post-reset watchdog stays disarmed until a first sample,
        // so only the gate's own deadline can end it — deterministic, not a timing window.
        LoadCellThread.GateResult result = gate.get(10, TimeUnit.SECONDS);

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
        CompletableFuture<LoadCellThread.GateResult> gate = cell.beginRecoveryGate(50f, 1000.0);
        // The reconnected stream turns out to be as poisoned as the first one.
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!gate.isDone() && System.nanoTime() < deadline) {
            cell.update(batch(Float.NaN, 1f, Float.NaN, 2f, Float.NaN));
            Thread.sleep(10);
        }

        LoadCellThread.GateResult result = gate.get(1, TimeUnit.SECONDS);
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
        verify(context, never()).sendSignal(anyInt());

        // endHold() is what turns limit signals back on (the caller re-arms the drive around it).
        cell.endHold();
        quickly().untilAsserted(() -> {
            cell.update(batch(152f));
            verify(context, atLeastOnce()).sendSignal(TestContext.RELEASE_SIGNAL);
        });
    }

    // ---- Loop crash escalation (LoadCellThread's catch-all around the measurement loop) ----

    @Test
    void loopCrashMidRunSafeStopsThenReportsAWatchdogFailureNeverABareStopSignal() {
        // Crash the loop body at its last statement, the limit decision: every exception on the
        // measurement thread takes the same catch, and the spy is the cheapest seam to throw from.
        doThrow(new RuntimeException("boom mid-run")).when(context).sendSignal(TestContext.RELEASE_SIGNAL);
        startLoop();

        cell.update(batch(150f)); // above the upper limit, so the crashing signal is reached

        quickly().until(() -> !cell.loopThread().isAlive());

        InOrder escalation = inOrder(motorSafety, lossListener);
        escalation.verify(motorSafety).safeStop(contains("load cell thread failed"));
        escalation.verify(lossListener).onWatchdogFailure(contains("boom mid-run"));
        // A crashed watchdog is a FAULT, not a finished run and not a sensor loss: no bare stop
        // signal (indistinguishable from a limit crossing) and no hold to resume from.
        verify(context, never()).sendSignal(0);
        verify(lossListener, never()).onSensorLoss(anyString(), anyFloat());
        verify(gapRecorder, never()).beginGap(anyString(), anyFloat());
    }

    @Test
    void watchdogFailureListenerCrashFallsBackToTheBareStopSignal() {
        doThrow(new RuntimeException("boom mid-run")).when(context).sendSignal(TestContext.RELEASE_SIGNAL);
        doThrow(new RuntimeException("listener down")).when(lossListener).onWatchdogFailure(anyString());
        startLoop();

        cell.update(batch(150f));

        // The documented last resort: with the typed FAULT path gone too, signal 0 still unblocks
        // the runner rather than leaving it parked in processSignals() forever.
        quickly().untilAsserted(() -> verify(context).sendSignal(0));
    }
}
