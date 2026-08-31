package ch.rupfizupfi.deck.testrunner;

import ch.rupfizupfi.deck.device.DeviceService;
import ch.rupfizupfi.deck.device.api.Drive;
import ch.rupfizupfi.deck.device.api.DriveProvider;
import ch.rupfizupfi.deck.device.frequencyinverter.DriveUnavailableException;
import ch.rupfizupfi.deck.device.frequencyinverter.FrequencyInverterDevice;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.mockito.InOrder;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class MotorSafetyControllerTest {

    private FrequencyInverterDevice device;
    private Drive drive;
    private DriveProvider driveProvider;
    private MotorSafetyController controller;

    /** Simulates the drive handle: false makes withDrive throw, i.e. the drive has gone silent. */
    private volatile boolean handleOpen = true;

    @BeforeEach
    void wireController() {
        device = mock(FrequencyInverterDevice.class);
        // Unstubbed getMotorSpeedValueAsRpm returns 0, so every stop verifies within ~50 ms unless a
        // test deliberately keeps the shaft turning. Only ONE test may burn the 5 s verify deadline.
        drive = mock(Drive.class);
        driveProvider = mock(DriveProvider.class);

        doAnswer(invocation -> {
            if (!handleOpen) {
                throw new DriveUnavailableException("handle closed");
            }
            invocation.<Consumer<Drive>>getArgument(0).accept(drive);
            return null;
        }).when(device).withDrive(any());
        doAnswer(invocation -> {
            invocation.<Runnable>getArgument(0).run();
            return null;
        }).when(device).runExclusive(any());

        DeviceService deviceService = mock(DeviceService.class);
        when(deviceService.getFrequencyInverter()).thenReturn(device);
        controller = new MotorSafetyController(deviceService, driveProvider);
    }

    @Test
    @Timeout(value = 15, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void clearStopLatchIsRefusedWhileAStopIsStillExecuting() throws Exception {
        CountDownLatch stopEntered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(invocation -> {
            stopEntered.countDown();
            release.await();
            invocation.<Consumer<Drive>>getArgument(0).accept(drive);
            return null;
        }).when(device).withDrive(any());

        Thread stopper = new Thread(() -> controller.safeStop("stop in flight"));
        stopper.start();
        try {
            assertThat(stopEntered.await(5, TimeUnit.SECONDS)).isTrue();

            // Clearing here would let the next energize() re-enable the drive this stop is de-energizing,
            // and resetting motorEnergized would make the NEXT stop read "nothing to stop".
            assertThatThrownBy(controller::clearStopLatch).isInstanceOf(IllegalStateException.class);
        } finally {
            // finally: the stopper thread must be released even when an assertion above fails.
            release.countDown();
        }
        stopper.join(5_000);
        assertThat(stopper.isAlive()).isFalse();
        assertThatCode(controller::clearStopLatch).doesNotThrowAnyException();
        assertThat(controller.isStopLatched()).isFalse();
    }

    @Test
    void stopCommandsGoOutGeneralEnableFirstThenReferenceThenStart() {
        SafeStopResult result = controller.safeStop("command order");

        InOrder order = inOrder(drive);
        order.verify(drive).setGeneralEnable(false);
        order.verify(drive).setSpeedReferenceValueAsRpm(0);
        order.verify(drive).setStart(false);
        order.verify(drive, atLeastOnce()).getMotorSpeedValueAsRpm();
        assertThat(result.stopped()).isTrue();
    }

    @Test
    void aFailingStopCommandStillLetsTheOtherTwoThrough() {
        doThrow(new RuntimeException("register write failed")).when(drive).setGeneralEnable(false);

        SafeStopResult result = controller.safeStop("partial command failure");

        verify(drive).setSpeedReferenceValueAsRpm(0);
        verify(drive).setStart(false);
        assertThat(result.stopped()).isTrue();
    }

    @Test
    void verificationNeedsTwoConsecutiveInToleranceReadings() {
        // Drive.getSpeedReferenceValueAsRpm does not exist - the interface itself enforces that a
        // stop can only ever be verified against the MEASURED speed, so only the pairing is tested.
        when(drive.getMotorSpeedValueAsRpm()).thenReturn(0, 500, 0, 0);

        SafeStopResult result = controller.safeStop("standstill must hold across a poll");

        assertThat(result.stopped()).isTrue();
        // Exactly 4 reads: the lone 0 before the 500 was not allowed to count toward the pair.
        verify(drive, times(4)).getMotorSpeedValueAsRpm();
    }

    @Test
    @Timeout(value = 15, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void respondingDriveWithATurningShaftIsCoastingAndNeverTearsDownTheUsb() {
        // The one test allowed to burn the full 5 s verify deadline (no injection seam exists).
        controller.energize(d -> {
        });
        when(drive.getMotorSpeedValueAsRpm()).thenReturn(500);

        SafeStopResult result = controller.safeStop("shaft keeps turning");

        assertThat(result.stopped()).isFalse();
        assertThat(result.coasting()).isTrue();
        assertThat(result.tier()).isEqualTo(SafeStopResult.Tier.EXISTING_HANDLE);
        verify(device, never()).dropConnectionBookkeeping();
        verify(device, never()).closeDriveHandle();
        verifyNoInteractions(driveProvider);
    }

    @Test
    void silentDriveOnARunThatNeverEnergizedHasNothingToStop() {
        handleOpen = false;

        SafeStopResult result = controller.safeStop("startup check failed");

        assertThat(result.motorWasEnergized()).isFalse();
        assertThat(result.needsOperatorAttention()).isFalse();
        assertThat(result.detail()).contains("no stop was needed");
        verify(device, never()).dropConnectionBookkeeping();
        verify(device, never()).closeDriveHandle();
        verifyNoInteractions(driveProvider);
    }

    @Test
    void silentDriveOnAnEnergizedRunEscalatesThroughFreshHandleToOperator() {
        controller.energize(d -> {
        });
        handleOpen = false;
        // OQ-50: close-then-open is ONE atomic step inside runExclusive, keeping Device.connect()
        // from opening a second live session on the one physical drive. An InOrder cannot see
        // lock extent, so the runExclusive stub raises a flag and the teardown stubs record it.
        AtomicBoolean insideExclusive = new AtomicBoolean();
        AtomicBoolean closedInsideExclusive = new AtomicBoolean();
        AtomicBoolean openedInsideExclusive = new AtomicBoolean();
        AtomicBoolean droppedInsideExclusive = new AtomicBoolean();
        doAnswer(invocation -> {
            insideExclusive.set(true);
            try {
                invocation.<Runnable>getArgument(0).run();
            } finally {
                insideExclusive.set(false);
            }
            return null;
        }).when(device).runExclusive(any());
        doAnswer(invocation -> {
            closedInsideExclusive.set(insideExclusive.get());
            return null;
        }).when(device).closeDriveHandle();
        doAnswer(invocation -> {
            droppedInsideExclusive.set(insideExclusive.get());
            return null;
        }).when(device).dropConnectionBookkeeping();
        when(driveProvider.open()).thenAnswer(invocation -> {
            openedInsideExclusive.set(insideExclusive.get());
            throw new RuntimeException("USB gone");
        });

        SafeStopResult result = controller.safeStop("silent drive");

        InOrder teardown = inOrder(device, driveProvider);
        teardown.verify(device).dropConnectionBookkeeping();
        teardown.verify(device).closeDriveHandle();
        teardown.verify(driveProvider).open();
        assertThat(closedInsideExclusive).as("closeDriveHandle must run inside runExclusive").isTrue();
        assertThat(openedInsideExclusive).as("the fresh open must run inside runExclusive").isTrue();
        // ...and the bookkeeping drop outside it: it takes the device's instance monitor, and
        // the lock order is monitor-before-driveLock.
        assertThat(droppedInsideExclusive).as("dropConnectionBookkeeping must run outside runExclusive").isFalse();
        assertThat(result.tier()).isEqualTo(SafeStopResult.Tier.NONE);
        assertThat(result.needsOperatorAttention()).isTrue();
        assertThat(result.detail()).contains("tier 3");
    }

    @Test
    void tierTwoStopVerifiesOverTheFreshHandleAndNeverReachesTierThree() {
        controller.energize(d -> {
        });
        handleOpen = false;
        // Unstubbed getMotorSpeedValueAsRpm reads 0, so the fresh handle verifies standstill fast.
        Drive fresh = mock(Drive.class);
        when(driveProvider.open()).thenReturn(fresh);

        SafeStopResult result = controller.safeStop("tier 1 handle dead, tier 2 healthy");

        assertThat(result.tier()).isEqualTo(SafeStopResult.Tier.FRESH_HANDLE);
        assertThat(result.stopped()).isTrue();
        assertThat(result.needsOperatorAttention()).isFalse();
        assertThat(result.detail()).doesNotContain("tier 3");
        // The stop commands went out over the fresh handle in safety order, and the handle was
        // closed again: tier 2 leaves the device handle-less on purpose (fail fast, never drive blind).
        InOrder commands = inOrder(fresh);
        commands.verify(fresh).setGeneralEnable(false);
        commands.verify(fresh).setSpeedReferenceValueAsRpm(0);
        commands.verify(fresh).setStart(false);
        verify(fresh).close();
        // The dead tier-1 handle never carried a command: withDrive threw before handing one out.
        verify(drive, never()).setStart(false);
    }

    @Test
    void safeStopNeverPropagatesEvenWhenTheTierDispatchItselfBlowsUp() {
        controller.energize(d -> {
        });
        handleOpen = false;
        // dropConnectionBookkeeping sits outside stopWithFreshHandle's try, so this explodes the tier
        // dispatch itself rather than a tier that already catches its own failures.
        doThrow(new IllegalStateException("dispatch boom")).when(device).dropConnectionBookkeeping();

        AtomicReference<SafeStopResult> result = new AtomicReference<>();
        assertThatCode(() -> result.set(controller.safeStop("emergency path"))).doesNotThrowAnyException();

        assertThat(result.get().tier()).isEqualTo(SafeStopResult.Tier.NONE);
        assertThat(result.get().detail()).contains("safeStop failed unexpectedly");
    }

    @Test
    void anEscalatedStopIsReplayedOnRepeatWithoutTouchingHardware() {
        controller.energize(d -> {
        });
        handleOpen = false;
        when(driveProvider.open()).thenThrow(new RuntimeException("USB gone"));
        SafeStopResult first = controller.safeStop("first stop escalates");
        clearInvocations(device, driveProvider, drive);

        SafeStopResult second = controller.safeStop("repeat from cleanup()");

        assertThat(second).isSameAs(first);
        verifyNoInteractions(device, driveProvider, drive);
    }

    @Test
    void clearStopLatchResetsTheRunSoTheNextStopIsFreshBookkeeping() {
        // First run: energized, dead handle, failed re-open - an escalated result gets cached.
        controller.energize(d -> {
        });
        handleOpen = false;
        when(driveProvider.open()).thenThrow(new RuntimeException("USB gone"));
        SafeStopResult first = controller.safeStop("first run escalates");
        assertThat(first.tier()).isEqualTo(SafeStopResult.Tier.NONE);

        // The next run starts here: latch cleared, motor known-off, no stop of its own on record.
        controller.clearStopLatch();
        assertThat(controller.isStopLatched()).isFalse();
        assertThat(controller.getLastStopResult()).isNull();
        handleOpen = true;
        clearInvocations(device, driveProvider, drive);

        SafeStopResult second = controller.safeStop("second run's own stop");

        // NOT a replay of the previous run's escalation: the hardware was genuinely commanded again
        // over the (healthy) existing handle...
        assertThat(second).isNotSameAs(first);
        assertThat(second.tier()).isEqualTo(SafeStopResult.Tier.EXISTING_HANDLE);
        assertThat(second.stopped()).isTrue();
        verify(drive).setStart(false);
        verifyNoInteractions(driveProvider);
        // ...and motorEnergized was reset: this run never energized, so its stop is bookkeeping.
        assertThat(second.motorWasEnergized()).isFalse();
    }

    @Test
    void aTierOneStopIsDeliberatelyReRunOnRepeat() {
        controller.energize(d -> {
        });
        SafeStopResult first = controller.safeStop("cleanup pass 1");
        SafeStopResult second = controller.safeStop("cleanup pass 2");

        assertThat(first.stopped()).isTrue();
        assertThat(second.stopped()).isTrue();
        // cleanup() runs twice by design; re-checking a motor over a working handle is worth repeating.
        verify(drive, times(2)).setStart(false);
    }

    @Test
    void energizeIsRefusedOnceAStopIsLatchedAndTheActionNeverRuns() {
        controller.safeStop("latch the stop");
        AtomicBoolean actionRan = new AtomicBoolean();

        assertThatThrownBy(() -> controller.energize(d -> actionRan.set(true)))
                .isInstanceOf(IllegalStateException.class);

        assertThat(actionRan).isFalse();
        // The refusal came from inside the drive action, i.e. the latch was checked under the drive
        // lock rather than in a checkable-then-stale spot before it.
        verify(device, times(2)).withDrive(any());
    }

    @Test
    void energizeMarksTheMotorLiveBeforeRunningTheAction() {
        assertThatThrownBy(() -> controller.energize(d -> {
            throw new RuntimeException("action died half-way");
        })).isInstanceOf(RuntimeException.class);
        handleOpen = false;
        when(driveProvider.open()).thenThrow(new RuntimeException("USB gone"));

        SafeStopResult result = controller.safeStop("after failed energize");

        // The failed action still enabled the output stage: a real stop with escalation, not the
        // "never energized" bookkeeping shortcut.
        assertThat(result.motorWasEnergized()).isTrue();
        assertThat(result.detail()).doesNotContain("no stop was needed");
        verify(driveProvider).open();
    }

    @Test
    @Timeout(value = 15, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void interruptedVerificationReportsTheAbortAndRestoresTheInterruptFlag() throws Exception {
        CountDownLatch firstRead = new CountDownLatch(1);
        when(drive.getMotorSpeedValueAsRpm()).thenAnswer(invocation -> {
            firstRead.countDown();
            return 500;
        });
        AtomicReference<SafeStopResult> result = new AtomicReference<>();
        AtomicBoolean interruptFlagRestored = new AtomicBoolean();
        Thread stopper = new Thread(() -> {
            result.set(controller.safeStop("operator stop"));
            interruptFlagRestored.set(Thread.currentThread().isInterrupted());
        });
        stopper.start();
        assertThat(firstRead.await(5, TimeUnit.SECONDS)).isTrue();
        stopper.interrupt();
        stopper.join(5_000);
        assertThat(stopper.isAlive()).isFalse();

        assertThat(result.get().stopped()).isFalse();
        assertThat(result.get().coasting()).isTrue();
        assertThat(result.get().detail()).contains("interrupt");
        assertThat(interruptFlagRestored).isTrue();
        // A truncated verification is not evidence of a dead handle: no tier 2 teardown.
        verify(device, never()).runExclusive(any());
    }
}
