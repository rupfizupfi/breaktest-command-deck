package ch.rupfizupfi.deck.testrunner;

import ch.rupfizupfi.deck.device.Device.LifecycleLockTimeoutException;
import ch.rupfizupfi.deck.device.DeviceService;
import ch.rupfizupfi.deck.device.api.ContractVersion;
import ch.rupfizupfi.deck.device.api.Drive;
import ch.rupfizupfi.deck.device.api.DriveClosePendingException;
import ch.rupfizupfi.deck.device.api.DriveCommunicationException;
import ch.rupfizupfi.deck.device.api.DriveDeadlineException;
import ch.rupfizupfi.deck.device.api.DriveGating;
import ch.rupfizupfi.deck.device.api.DriveLane;
import ch.rupfizupfi.deck.device.api.DriveProvider;
import ch.rupfizupfi.deck.device.api.StaleStopEpochException;
import ch.rupfizupfi.deck.device.frequencyinverter.DriveUnavailableException;
import ch.rupfizupfi.deck.device.frequencyinverter.FrequencyInverterDevice;
import ch.rupfizupfi.deck.device.simulated.SimulatedBench;
import ch.rupfizupfi.deck.device.simulated.SimulatedBenchProperties;
import ch.rupfizupfi.deck.device.simulated.SimulatedDriveProvider;
import ch.rupfizupfi.deck.device.simulated.SimulatedFaultSwitches;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.mockito.InOrder;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doCallRealMethod;
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
        drive = quietDrive();
        driveProvider = mock(DriveProvider.class);

        doAnswer(invocation -> {
            if (!handleOpen) {
                throw new DriveUnavailableException("handle closed");
            }
            invocation.<Consumer<Drive>>getArgument(0).accept(drive);
            return null;
        }).when(device).withDrive(any());
        doAnswer(invocation -> {
            if (!handleOpen) {
                throw new DriveUnavailableException("handle closed");
            }
            return invocation.<Function<Drive, Object>>getArgument(0).apply(drive);
        }).when(device).queryDrive(any());
        doAnswer(invocation -> {
            invocation.<Runnable>getArgument(0).run();
            return null;
        }).when(device).runExclusive(any());

        controller = new MotorSafetyController(deviceServiceFor(device), driveProvider, new DriveLinkProperties());
    }

    private static DeviceService deviceServiceFor(FrequencyInverterDevice target) {
        DeviceService deviceService = mock(DeviceService.class);
        when(deviceService.getFrequencyInverter()).thenReturn(target);
        return deviceService;
    }

    private static final Map<String, Boolean> DE_ENERGIZED = Map.of("start", false, "generalEnable", false,
            "useSecondRamp", false, "directionIsForward", true);
    private static final Map<String, Boolean> ENERGIZED = Map.of("start", true, "generalEnable", true,
            "useSecondRamp", false, "directionIsForward", true);

    /**
     * A gated drive whose control word reads back de-energized; speed reads 0 unless stubbed. Its
     * stop epoch counts {@link Drive#stop()} calls, and a linked group runs its body on the drive
     * itself unless a stop moved the epoch past the group's fence.
     */
    private static Drive quietDrive() {
        Drive quiet = mock(Drive.class);
        AtomicLong epoch = new AtomicLong();
        when(quiet.getControlParameters()).thenReturn(DE_ENERGIZED);
        when(quiet.gating()).thenReturn(DriveGating.DRIVER_SERIALIZES);
        when(quiet.stopEpoch()).thenAnswer(invocation -> epoch.get());
        doAnswer(invocation -> {
            epoch.incrementAndGet();
            return null;
        }).when(quiet).stop();
        doAnswer(invocation -> {
            long expected = invocation.getArgument(0);
            if (expected != epoch.get()) {
                throw new StaleStopEpochException("group rejected", expected, epoch.get());
            }
            invocation.<Consumer<Drive>>getArgument(1).accept(quiet);
            return null;
        }).when(quiet).runLinked(anyLong(), any());
        return quiet;
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
    void tierOneCommandsTheStopWithOneStopCallBeforeVerifying() {
        SafeStopResult result = controller.safeStop("command order");

        InOrder order = inOrder(drive);
        // Drive#stop(): one telegram on a gated driver, so general enable can never trail start
        // (safety-and-recovery.md).
        order.verify(drive).stop();
        order.verify(drive, atLeastOnce()).getMotorSpeedValueAsRpm();
        // No single-bit writes of the deck's own beside it.
        verify(drive, never()).setGeneralEnable(false);
        verify(drive, never()).setStart(false);
        verify(drive, never()).setSpeedReferenceValueAsRpm(0);
        verify(drive, never()).setControlParameters(any(), any(), any(), any(), any());
        assertThat(result.verified()).isTrue();
        assertThat(result.detail()).contains("general enable and start read back clear");
    }

    @Test
    void aFailedStopCallIsCompletedByTheVerificationsReSend() {
        doThrow(new RuntimeException("register write failed")).when(drive).stop();
        when(drive.getControlParameters()).thenReturn(ENERGIZED, DE_ENERGIZED);

        SafeStopResult result = controller.safeStop("stop command failure");

        verify(drive).setControlParameters(false, false, null, null, null);
        assertThat(result.verified()).isTrue();
        assertThat(result.detail()).startsWith("stop command failed: ");
    }

    @Test
    void oneDroppedFrameOnTheStopIsReSentAndNeverTearsDownTheHandle() {
        // H-06: the first de-energize write is lost; the drive still answers. The control word reads
        // back energized until the re-send lands, then clear.
        controller.energize(d -> {
        });
        AtomicBoolean landed = new AtomicBoolean();
        doThrow(new RuntimeException("Timeout")).when(drive).stop();
        doAnswer(invocation -> {
            landed.set(true);
            return null;
        }).when(drive).setControlParameters(false, false, null, null, null);
        when(drive.getControlParameters()).thenAnswer(invocation -> landed.get() ? DE_ENERGIZED : ENERGIZED);

        SafeStopResult result = controller.safeStop("one dropped frame");

        assertThat(result.tier()).isEqualTo(SafeStopResult.Tier.EXISTING_HANDLE);
        assertThat(result.verified()).isTrue();
        assertThat(result.driveResponsive()).isTrue();
        assertThat(result.detail()).contains("after 1 re-sent de-energize write(s)");
        verify(drive).stop();
        verify(drive, times(1)).setControlParameters(false, false, null, null, null);
        verify(device, never()).invalidateConnection();
        verifyNoInteractions(driveProvider);
    }

    @Test
    void theStopIsReSentUntilTheControlWordReadsBackClear() {
        // The write was acknowledged but the drive still reads energized twice: keep re-sending.
        when(drive.getControlParameters()).thenReturn(ENERGIZED, ENERGIZED, DE_ENERGIZED);

        SafeStopResult result = controller.safeStop("write acknowledged, word not clear");

        assertThat(result.verified()).isTrue();
        // The stop, then one re-send per energized read-back.
        verify(drive).stop();
        verify(drive, times(2)).setControlParameters(false, false, null, null, null);
        // Confirmed once, never re-read: the poll goes back to speed reads only.
        verify(drive, times(3)).getControlParameters();
    }

    @Test
    void standstillWithoutADeEnergizeReadBackIsNotAVerifiedStop() {
        var quick = new MotorSafetyController(deviceServiceFor(device), driveProvider,
                new DriveLinkProperties(), 300);
        when(drive.getControlParameters()).thenReturn(ENERGIZED);
        Drive fresh = quietDrive();
        when(fresh.getControlParameters()).thenReturn(ENERGIZED);
        when(driveProvider.open()).thenReturn(fresh);
        quick.energize(d -> {
        });

        SafeStopResult result = quick.safeStop("drive ignores general enable off");

        // A drive that answers but will not drop general enable is not a working stop.
        assertThat(result.verified()).isFalse();
        assertThat(result.tier()).isEqualTo(SafeStopResult.Tier.OPERATOR_ESCALATION);
        assertThat(result.needsOperatorAttention()).isTrue();
        assertThat(result.detail()).contains("never confirmed it is de-energized");
    }

    @Test
    void verificationNeedsTwoConsecutiveInToleranceReadings() {
        // Drive exposes only the measured speed, so only the pairing is tested.
        when(drive.getMotorSpeedValueAsRpm()).thenReturn(0, 500, 0, 0);

        SafeStopResult result = controller.safeStop("standstill must hold across a poll");

        assertThat(result.verified()).isTrue();
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

        assertThat(result.verified()).isFalse();
        assertThat(result.coasting()).isTrue();
        assertThat(result.tier()).isEqualTo(SafeStopResult.Tier.EXISTING_HANDLE);
        verify(device, never()).invalidateConnection();
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
        verify(device, never()).invalidateConnection();
        verify(device, never()).closeDriveHandle();
        verifyNoInteractions(driveProvider);
    }

    @Test
    void silentDriveOnAnEnergizedRunEscalatesThroughFreshHandleToOperator() {
        controller.energize(d -> {
        });
        handleOpen = false;
        // OQ-50: close, fresh open and the lost mark are ONE atomic step inside runExclusive,
        // keeping Device.connect() from opening a second live session on the one physical drive.
        // An InOrder cannot see monitor extent, so the runExclusive stub raises a flag and the
        // teardown stubs record it.
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
        }).when(device).invalidateConnection();
        when(driveProvider.open()).thenAnswer(invocation -> {
            openedInsideExclusive.set(insideExclusive.get());
            throw new RuntimeException("USB gone");
        });

        SafeStopResult result = controller.safeStop("silent drive");

        InOrder teardown = inOrder(device, driveProvider);
        teardown.verify(device).closeDriveHandle();
        teardown.verify(driveProvider).open();
        // Last: the handle is marked lost only once the old one is closed (H-09).
        teardown.verify(device).invalidateConnection();
        assertThat(closedInsideExclusive).as("closeDriveHandle must run inside runExclusive").isTrue();
        assertThat(openedInsideExclusive).as("the fresh open must run inside runExclusive").isTrue();
        // ...and the lost mark as well, so no connect() lands between the fresh close and the mark.
        assertThat(droppedInsideExclusive).as("invalidateConnection must run inside runExclusive").isTrue();
        assertThat(result.tier()).isEqualTo(SafeStopResult.Tier.OPERATOR_ESCALATION);
        assertThat(result.needsOperatorAttention()).isTrue();
        assertThat(result.detail()).contains("tier 3");
    }

    /**
     * BL-135/BL-148: the old handle's close gave up at the contract's 1500 ms bound with a transfer
     * still in flight. Tier 2 opens no fresh handle beside it, and tier 3 is raised within the
     * stated bound - tier 1's end plus the close bound plus slack - however long the transfer stays
     * wedged. The close is stubbed at its worst case.
     */
    @Test
    @Timeout(value = 15, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void aPendingHandleCloseSkipsTheFreshHandleAndEscalatesToTheOperatorWithinTheTierTwoBound() {
        controller.energize(d -> {
        });
        handleOpen = false;
        doAnswer(invocation -> {
            Thread.sleep(DriveLane.STOP_DEADLINE_MILLIS);
            return FrequencyInverterDevice.HandleClose.PENDING;
        }).when(device).closeDriveHandle();

        long started = System.nanoTime();
        SafeStopResult result = controller.safeStop("transfer wedged past the close bound");
        long tookMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

        verifyNoInteractions(driveProvider);
        verify(device).closeDriveHandle();
        verify(device).invalidateConnection();
        assertThat(tookMs).as("tier 3 within the close bound + slack")
                .isLessThanOrEqualTo(DriveLane.STOP_DEADLINE_MILLIS + 500);
        assertThat(result.tier()).isEqualTo(SafeStopResult.Tier.OPERATOR_ESCALATION);
        assertThat(result.verified()).isFalse();
        assertThat(result.needsOperatorAttention()).isTrue();
        assertThat(result.detail())
                .contains("tier 2 (FRESH_HANDLE) not attempted")
                .contains("still in flight")
                .contains("tier 1 failed and tier 2 could not run")
                .contains("USE THE PHYSICAL E-STOP")
                .doesNotContain("re-enumerated");
    }

    /** Tier 2's own handle can wedge too: its pending close is parked so the next connect waits for it. */
    @Test
    void aFreshHandleWhoseCloseIsPendingIsParkedOnTheDevice() {
        controller.energize(d -> {
        });
        handleOpen = false;
        Drive fresh = quietDrive();
        doThrow(new DriveClosePendingException("fresh transfer still in flight")).when(fresh).close();
        when(driveProvider.open()).thenReturn(fresh);

        SafeStopResult result = controller.safeStop("fresh handle wedges on close");

        assertThat(result.tier()).isEqualTo(SafeStopResult.Tier.FRESH_HANDLE);
        assertThat(result.verified()).isTrue();
        verify(device).parkPendingClose(fresh);
    }

    /** Any other close failure is best effort, as before: the fresh handle still opens and stops. */
    @Test
    void aFailedHandleCloseStillOpensTheFreshHandle() {
        controller.energize(d -> {
        });
        handleOpen = false;
        when(device.closeDriveHandle()).thenReturn(FrequencyInverterDevice.HandleClose.FAILED);
        Drive fresh = quietDrive();
        when(driveProvider.open()).thenReturn(fresh);

        SafeStopResult result = controller.safeStop("old handle failed to close");

        assertThat(result.tier()).isEqualTo(SafeStopResult.Tier.FRESH_HANDLE);
        assertThat(result.verified()).isTrue();
        verify(fresh).stop();
    }

    @Test
    void tierTwoStopVerifiesOverTheFreshHandleAndNeverReachesTierThree() {
        controller.energize(d -> {
        });
        handleOpen = false;
        // Unstubbed getMotorSpeedValueAsRpm reads 0, so the fresh handle verifies standstill fast.
        Drive fresh = quietDrive();
        when(driveProvider.open()).thenReturn(fresh);

        SafeStopResult result = controller.safeStop("tier 1 handle dead, tier 2 healthy");

        assertThat(result.tier()).isEqualTo(SafeStopResult.Tier.FRESH_HANDLE);
        assertThat(result.verified()).isTrue();
        assertThat(result.needsOperatorAttention()).isFalse();
        assertThat(result.detail()).doesNotContain("tier 3");
        // The stop went out over the fresh handle as one Drive#stop(), and the handle was closed
        // again: tier 2 leaves the device handle-less on purpose (fail fast, never drive blind).
        InOrder commands = inOrder(fresh);
        commands.verify(fresh).stop();
        commands.verify(fresh).close();
        // The dead tier-1 handle never carried a command: withDrive threw before handing one out.
        verify(drive, never()).stop();
    }

    @Test
    void safeStopNeverPropagatesEvenWhenTheTierDispatchItselfBlowsUp() {
        controller.energize(d -> {
        });
        handleOpen = false;
        // invalidateConnection sits in stopWithFreshHandle's finally, outside the tier's own catch, so
        // this explodes the tier dispatch itself rather than a tier that already catches its failures.
        doThrow(new IllegalStateException("dispatch boom")).when(device).invalidateConnection();

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

        assertThat(first.tier()).isEqualTo(SafeStopResult.Tier.OPERATOR_ESCALATION);
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
        assertThat(first.tier()).isEqualTo(SafeStopResult.Tier.OPERATOR_ESCALATION);

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
        assertThat(second.verified()).isTrue();
        verify(drive).stop();
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

        assertThat(first.verified()).isTrue();
        assertThat(second.verified()).isTrue();
        // cleanup() runs twice by design; re-checking a motor over a working handle is worth repeating.
        verify(drive, times(2)).stop();
    }

    @Test
    void energizeIsRefusedOnceAStopIsLatchedAndTheActionNeverRuns() {
        controller.safeStop("latch the stop");
        AtomicBoolean actionRan = new AtomicBoolean();

        assertThatThrownBy(() -> controller.energize(d -> actionRan.set(true)))
                .isInstanceOf(IllegalStateException.class);

        assertThat(actionRan).isFalse();
        // The latch was checked after the stop epoch was read, so it is the fence's check, and no
        // group was submitted.
        InOrder fence = inOrder(drive);
        fence.verify(drive).stopEpoch();
        verify(drive, never()).runLinked(anyLong(), any());
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

        assertThat(result.get().verified()).isFalse();
        assertThat(result.get().coasting()).isTrue();
        assertThat(result.get().detail()).contains("interrupt");
        assertThat(interruptFlagRestored).isTrue();
        // A truncated verification is not evidence of a dead handle: no tier 2 teardown.
        verify(device, never()).runExclusive(any());
    }

    /**
     * The contract's deadline exception is the "wedged" tier outcome: the stop missed the STOP lane's
     * deadline behind a transaction still in flight, so tier 1 sends nothing more, and tier 2 is not
     * tried - a fresh handle beside that transaction would be a second session (OQ-50).
     */
    @Test
    void aStopThatMissesItsDeadlineEscalatesStraightToTheOperatorWithoutATierTwoSession() {
        controller.energize(d -> {
        });
        doThrow(new DriveDeadlineException(DriveLane.STOP, "stop not answered within 1500 ms"))
                .when(drive).stop();

        SafeStopResult result = controller.safeStop("runner wedged in a native call");

        assertThat(result.tier()).isEqualTo(SafeStopResult.Tier.OPERATOR_ESCALATION);
        assertThat(result.needsOperatorAttention()).isTrue();
        assertThat(result.detail()).contains("unreachable").contains("PHYSICAL E-STOP")
                .contains("stop not answered within 1500 ms")
                // Operator-facing: the line must not claim a tier that never touched the drive.
                .contains("tier 2 (FRESH_HANDLE) not attempted")
                .contains("tier 1 failed and tier 2 could not run")
                .doesNotContain("re-enumerated");
        // Nothing more over the wedged handle, and no second session beside it.
        verify(drive, never()).getMotorSpeedValueAsRpm();
        verify(drive, never()).setControlParameters(any(), any(), any(), any(), any());
        verify(device, never()).runExclusive(any());
        verify(device, never()).closeDriveHandle();
        verifyNoInteractions(driveProvider);
    }

    /**
     * Any other comms failure on the stop is a lossy link, not a wedge: tier 1 still verifies, and a
     * handle that answers nothing goes on to tier 2.
     */
    @Test
    void aStopLostOnALossyLinkStillVerifiesAndThenTriesTheFreshHandle() {
        controller.energize(d -> {
        });
        doThrow(new DriveCommunicationException("no reply")).when(drive).stop();
        when(drive.getMotorSpeedValueAsRpm()).thenThrow(new DriveCommunicationException("no reply"));
        when(drive.getControlParameters()).thenThrow(new DriveCommunicationException("no reply"));
        Drive fresh = quietDrive();
        when(driveProvider.open()).thenReturn(fresh);

        SafeStopResult result = controller.safeStop("lossy link");

        assertThat(result.tier()).isEqualTo(SafeStopResult.Tier.FRESH_HANDLE);
        assertThat(result.verified()).isTrue();
        verify(drive, atLeastOnce()).getMotorSpeedValueAsRpm();
        verify(fresh).stop();
    }

    @Test
    void latchStopRefusesEnergizeWithoutCommandingTheDrive() {
        controller.latchStop("stop pressed during startup");
        AtomicBoolean actionRan = new AtomicBoolean();

        assertThatThrownBy(() -> controller.energize(d -> actionRan.set(true)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("stop pressed during startup");

        assertThat(actionRan).isFalse();
        verify(drive, never()).setControlParameters(any(), any(), any(), any(), any());
        verify(drive, never()).stop();
        verify(drive, never()).runLinked(anyLong(), any());
    }

    @Test
    @Timeout(value = 15, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void theLatchStaysUnclearableUntilTheLastOfTwoConcurrentStopsFinishes() throws Exception {
        CountDownLatch bothEntered = new CountDownLatch(2);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch releaseSecond = new CountDownLatch(1);
        AtomicBoolean firstTaken = new AtomicBoolean();
        doAnswer(invocation -> {
            CountDownLatch mine = firstTaken.compareAndSet(false, true) ? releaseFirst : releaseSecond;
            bothEntered.countDown();
            mine.await();
            invocation.<Consumer<Drive>>getArgument(0).accept(drive);
            return null;
        }).when(device).withDrive(any());

        Thread a = new Thread(() -> controller.safeStop("measurement thread"));
        Thread b = new Thread(() -> controller.safeStop("runner cleanup"));
        a.start();
        b.start();
        try {
            assertThat(bothEntered.await(5, TimeUnit.SECONDS)).isTrue();
            releaseFirst.countDown();
            // One stop is done, the other is still de-energizing: a boolean guard would allow this.
            Thread.sleep(500);
            assertThatThrownBy(controller::clearStopLatch).isInstanceOf(IllegalStateException.class);
        } finally {
            releaseFirst.countDown();
            releaseSecond.countDown();
        }
        a.join(5_000);
        b.join(5_000);
        assertThatCode(controller::clearStopLatch).doesNotThrowAnyException();
    }

    @Test
    void energizeArmsTheLinkLossStopBeforeTheActionRunsInOneFencedGroup() {
        controller.energize(d -> d.setGeneralEnable(true));

        InOrder order = inOrder(drive);
        // The epoch is read before the latch check, and the whole sequence is one group fenced on it.
        order.verify(drive).stopEpoch();
        order.verify(drive).runLinked(eq(0L), any());
        order.verify(drive).setActionInCaseOfCommunicationError(Drive.COMMUNICATION_ERROR_DISABLE_VIA_GENERAL_ENABLE);
        order.verify(drive).enableLinkLossStop(Duration.ofSeconds(2));
        order.verify(drive).setGeneralEnable(true);
    }

    @Test
    void everyEnergizeRearmsTheLinkLossStop() {
        controller.energize(d -> d.setGeneralEnable(true));
        controller.safeStop("hold");
        controller.clearStopLatch();
        controller.energize(d -> d.setGeneralEnable(true));

        // A resume re-energizes through the same path, so it re-arms as well.
        verify(drive, times(2)).enableLinkLossStop(Duration.ofSeconds(2));
    }

    @Test
    void aPluginOlderThanTheLinkLossStopIsRefusedBeforeAnythingIsEnergized() {
        // A jar built against contract 1.1.0 inherits the default, which throws.
        doCallRealMethod().when(drive).enableLinkLossStop(any());
        AtomicBoolean actionRan = new AtomicBoolean();

        assertThatThrownBy(() -> controller.energize(d -> actionRan.set(true)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("cannot stop itself on a lost link")
                .hasMessageContaining("1.2.0")
                .hasCauseInstanceOf(UnsupportedOperationException.class);

        assertThat(actionRan).isFalse();
        verify(drive, never()).setGeneralEnable(true);
        // Nothing was energized, so the run's teardown has nothing to chase.
        assertThat(controller.safeStop("after refused energize").motorWasEnergized()).isFalse();
    }

    @Test
    void aDriveThatRejectsTheTimeoutIsNotEnergized() {
        doThrow(new IllegalStateException("P0314 read back 0")).when(drive).enableLinkLossStop(any());
        AtomicBoolean actionRan = new AtomicBoolean();

        assertThatThrownBy(() -> controller.energize(d -> actionRan.set(true)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("P0314 read back 0");

        assertThat(actionRan).isFalse();
    }

    @Test
    void theTierThreeMessageNamesTheArmedTimeout() {
        controller.energize(d -> d.setGeneralEnable(true));
        handleOpen = false;
        when(driveProvider.open()).thenThrow(new RuntimeException("USB gone"));

        SafeStopResult result = controller.safeStop("drive gone");

        assertThat(result.tier()).isEqualTo(SafeStopResult.Tier.OPERATOR_ESCALATION);
        assertThat(result.detail())
                .contains("link-loss stop, armed at energize")
                .contains("2000 ms")
                .doesNotContain("does not write yet");
    }

    /**
     * H-09 / OQ-50 over a REAL {@link FrequencyInverterDevice}: a dashboard {@code connect()} fired
     * at the end of tier 2's exclusive section — the gap before the lost mark — must never leave
     * two sessions open on the one drive, and must not leave a handle nobody will close.
     */
    @Test
    @Timeout(value = 15, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void aConnectDuringTierTwoNeverStacksASecondSessionOnTheDrive() throws Exception {
        AtomicInteger live = new AtomicInteger();
        AtomicInteger maxLive = new AtomicInteger();
        AtomicBoolean oldHandleDead = new AtomicBoolean();
        AtomicReference<Thread> connector = new AtomicReference<>();
        AtomicReference<FrequencyInverterDevice> realDevice = new AtomicReference<>();

        Drive old = trackedDrive(live);
        when(old.getMotorSpeedValueAsRpm()).thenAnswer(invocation -> {
            if (oldHandleDead.get()) {
                throw new RuntimeException("old handle dead");
            }
            return 0;
        });
        when(old.getControlParameters()).thenAnswer(invocation -> {
            if (oldHandleDead.get()) {
                throw new RuntimeException("old handle dead");
            }
            return DE_ENERGIZED;
        });
        Drive fresh = trackedDrive(live);
        // Inside tier 2's exclusive section: fire the dashboard's connect() right here and let it
        // get as far as it can (parked on the device's lifecycle lock, or done) before the tier ends.
        doAnswer(invocation -> {
            Thread t = new Thread(() -> realDevice.get().connect(), "dashboard-connect");
            connector.set(t);
            t.start();
            long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (t.isAlive() && t.getState() != Thread.State.BLOCKED && t.getState() != Thread.State.WAITING
                    && System.nanoTime() < until) {
                Thread.onSpinWait();
            }
            live.decrementAndGet();
            return null;
        }).when(fresh).close();
        Drive later = trackedDrive(live);
        var opened = new ConcurrentLinkedQueue<>(List.of(old, fresh, later));
        DriveProvider tracking = () -> {
            Drive next = opened.remove();
            maxLive.accumulateAndGet(live.incrementAndGet(), Math::max);
            return next;
        };

        var real = new FrequencyInverterDevice(tracking);
        realDevice.set(real);
        var quick = new MotorSafetyController(deviceServiceFor(real), tracking, new DriveLinkProperties(), 300);
        real.connect();
        quick.energize(d -> {
        });
        oldHandleDead.set(true);

        SafeStopResult result = quick.safeStop("old handle died mid-run");

        connector.get().join(5_000);
        assertThat(connector.get().isAlive()).isFalse();
        assertThat(result.tier()).isEqualTo(SafeStopResult.Tier.FRESH_HANDLE);
        assertThat(result.verified()).isTrue();
        assertThat(maxLive).as("sessions open on the drive at once").hasValue(1);
        // The connect waited for the whole tier, lost mark included, so it found the handle lost and
        // opened the only live session.
        assertThat(live).as("sessions open after tier 2").hasValue(1);
        assertThat(real.isDriveHandleOpen()).isTrue();

        // The run's and the dashboard's references survived the loss (OQ-68), so the session closes
        // only when both holders are gone.
        real.disconnect();
        assertThat(live).hasValue(1);
        real.disconnect();
        assertThat(live).hasValue(0);
    }

    // ---- tier 2's bounded wait for the lifecycle lock (BL-212) ------------------------------------

    /** A safeStop whose tier 1 fails at once reports tier 3 within this, however long the lock is held. */
    private static final long SAFE_STOP_LOCK_ESCALATION_BOUND_MS = 3_000;

    /**
     * Over a REAL {@link FrequencyInverterDevice}: an earlier escalation left the handle lost and a
     * dashboard {@code connect()} is parked inside {@link DriveProvider#open()}, which the contract
     * does not bound. Tier 1 finds no handle; tier 2 gives up on the lifecycle lock at
     * {@link FrequencyInverterDevice#RUN_EXCLUSIVE_WAIT_MS} and reports tier 3 naming the holder,
     * without waiting for that open. It opens nothing itself, so the connect's session stays the
     * only one on the drive (OQ-50), and the next stop is not a replay: its tier 1 stops the motor
     * over the session the connect left.
     */
    @Test
    @Timeout(value = 20, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void aTierTwoBehindAConnectWedgedInOpenEscalatesAtTheLockBoundAndOpensNoSecondSession() throws Exception {
        AtomicInteger live = new AtomicInteger();
        AtomicInteger maxLive = new AtomicInteger();
        AtomicInteger opens = new AtomicInteger();
        CountDownLatch inOpen = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        DriveProvider tracking = () -> {
            if (opens.incrementAndGet() == 2) {
                inOpen.countDown();
                awaitQuietly(release);
            }
            Drive next = trackedDrive(live);
            maxLive.accumulateAndGet(live.incrementAndGet(), Math::max);
            return next;
        };
        var real = new FrequencyInverterDevice(tracking);
        var quick = new MotorSafetyController(deviceServiceFor(real), tracking, new DriveLinkProperties(), 300);
        real.connect();
        quick.energize(d -> {
        });
        real.invalidateConnection();
        Thread connector = Thread.ofPlatform().name("dashboard-connect").start(real::connect);
        assertThat(inOpen.await(5, TimeUnit.SECONDS)).isTrue();

        long started = System.nanoTime();
        SafeStopResult result = quick.safeStop("handle lost while the dashboard re-opens it");
        long tookMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        release.countDown();
        connector.join(5_000);

        assertThat(tookMs).as("tier 3 at the lock bound, within the stated 3000 ms, not after open() returns")
                .isGreaterThanOrEqualTo(FrequencyInverterDevice.RUN_EXCLUSIVE_WAIT_MS - 1)
                .isLessThan(SAFE_STOP_LOCK_ESCALATION_BOUND_MS);
        assertThat(result.tier()).isEqualTo(SafeStopResult.Tier.OPERATOR_ESCALATION);
        assertThat(result.verified()).isFalse();
        assertThat(result.needsOperatorAttention()).isTrue();
        assertThat(result.detail())
                .contains("tier 2 (FRESH_HANDLE) not attempted")
                .contains("lifecycle lock")
                .contains("connect()")
                .contains("dashboard-connect")
                .contains("USE THE PHYSICAL E-STOP")
                .doesNotContain("re-enumerated");
        assertThat(opens).as("the run's open and the dashboard's, never one by tier 2").hasValue(2);
        assertThat(maxLive).as("sessions open on the drive at once").hasValue(1);
        assertThat(connector.isAlive()).isFalse();
        assertThat(real.isDriveHandleOpen()).isTrue();

        SafeStopResult retried = quick.safeStop("run cleanup after the dashboard's connect");
        assertThat(retried).isNotSameAs(result);
        assertThat(retried.tier()).isEqualTo(SafeStopResult.Tier.EXISTING_HANDLE);
        assertThat(retried.verified()).isTrue();
        assertThat(opens).hasValue(2);

        // The run kept its reference across the loss (OQ-68): both holders release the one session.
        real.disconnect();
        real.disconnect();
        assertThat(live).hasValue(0);
    }

    /**
     * Over a REAL device: the run's {@code disconnect()} is inside the handle's close, wedged at the
     * contract's 1500 ms bound, when tier 1 finds no handle. Tier 2 gets the lifecycle lock once the
     * close ends - inside its bound - and runs a real fresh-handle stop; never two sessions at once.
     */
    @Test
    @Timeout(value = 20, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void aTierTwoBehindADisconnectWedgedInCloseRunsOnceTheCloseEnds() throws Exception {
        AtomicInteger live = new AtomicInteger();
        AtomicInteger maxLive = new AtomicInteger();
        CountDownLatch inClose = new CountDownLatch(1);
        Drive old = trackedDrive(live);
        doAnswer(invocation -> {
            inClose.countDown();
            Thread.sleep(DriveLane.STOP_DEADLINE_MILLIS);
            live.decrementAndGet();
            return null;
        }).when(old).close();
        Drive fresh = trackedDrive(live);
        var opened = new ConcurrentLinkedQueue<>(List.of(old, fresh));
        DriveProvider tracking = () -> {
            Drive next = opened.remove();
            maxLive.accumulateAndGet(live.incrementAndGet(), Math::max);
            return next;
        };
        var real = new FrequencyInverterDevice(tracking);
        var quick = new MotorSafetyController(deviceServiceFor(real), tracking, new DriveLinkProperties(), 300);
        real.connect();
        quick.energize(d -> {
        });
        Thread disconnector = Thread.ofPlatform().name("run-disconnect").start(real::disconnect);
        assertThat(inClose.await(5, TimeUnit.SECONDS)).isTrue();

        long started = System.nanoTime();
        SafeStopResult result = quick.safeStop("the run's disconnect is closing the handle");
        long tookMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        disconnector.join(5_000);

        assertThat(tookMs).as("the close's remainder plus the fresh stop, inside the lock bound")
                .isLessThan(FrequencyInverterDevice.RUN_EXCLUSIVE_WAIT_MS);
        assertThat(result.tier()).isEqualTo(SafeStopResult.Tier.FRESH_HANDLE);
        assertThat(result.verified()).isTrue();
        verify(fresh).stop();
        assertThat(maxLive).as("sessions open on the drive at once").hasValue(1);
        assertThat(live).as("the disconnect closed the old session, tier 2 its own").hasValue(0);
        assertThat(disconnector.isAlive()).isFalse();
        assertThat(real.isDriveHandleOpen()).isFalse();
    }

    /**
     * A timed-out lock means nothing ran: no handle closed, opened or marked lost outside it. Unlike
     * every other escalation past tier 1 it is not replayed: the next stop runs the ladder again.
     */
    @Test
    void aTierTwoThatTimesOutOnTheLifecycleLockTouchesNoHandleAndIsRetriedByTheNextStop() {
        controller.energize(d -> {
        });
        handleOpen = false;
        doThrow(new LifecycleLockTimeoutException("the frequency inverter's lifecycle lock was not free"
                + " within 2500 ms, held by connect() on thread 'dashboard-connect', held for 2501 ms"))
                .doAnswer(invocation -> {
                    invocation.<Runnable>getArgument(0).run();
                    return null;
                })
                .when(device).runExclusive(any());

        SafeStopResult first = controller.safeStop("lifecycle lock held");

        assertThat(first.tier()).isEqualTo(SafeStopResult.Tier.OPERATOR_ESCALATION);
        assertThat(first.verified()).isFalse();
        assertThat(first.needsOperatorAttention()).isTrue();
        assertThat(first.detail())
                .contains("tier 2 (FRESH_HANDLE) not attempted")
                .contains("lifecycle lock")
                .contains("no fresh handle was opened")
                .contains("tier 1 failed and tier 2 could not run");
        assertThat(controller.getLastStopResult()).isSameAs(first);
        verifyNoInteractions(driveProvider);
        verify(device, never()).closeDriveHandle();
        verify(device, never()).invalidateConnection();
        verify(device, never()).parkPendingClose(any());

        Drive fresh = quietDrive();
        when(driveProvider.open()).thenReturn(fresh);
        when(device.closeDriveHandle()).thenReturn(FrequencyInverterDevice.HandleClose.CLOSED);
        SafeStopResult second = controller.safeStop("the holder is done");

        assertThat(second).isNotSameAs(first);
        assertThat(second.tier()).isEqualTo(SafeStopResult.Tier.FRESH_HANDLE);
        assertThat(second.verified()).isTrue();
        verify(device, times(2)).runExclusive(any());
        verify(fresh).stop();
        verify(device).invalidateConnection();
        // A tier 2 that ran is replayed as before.
        assertThat(controller.safeStop("cleanup")).isSameAs(second);
        verify(device, times(2)).runExclusive(any());
    }

    /**
     * Over a REAL device: two stop callers (the detector and the runner) both reach tier 2, and the
     * first one's verify of a coasting motor holds the lifecycle lock past the bound. The second
     * reports tier 3 at the bound naming that tier 2 - a bounded false alarm, never a second session.
     * Here the first finishes a second after the bound, so its verified result is written last and
     * is the one the run keeps; nothing orders the two writes when the first finishes right at the
     * bound, which is why the false tier 3 is retryable.
     */
    @Test
    @Timeout(value = 20, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void aSecondStopCallerBehindAnotherTierTwoReportsTierThreeWithinTheBoundNamingThatTier() throws Exception {
        long coastMs = FrequencyInverterDevice.RUN_EXCLUSIVE_WAIT_MS + 1_000;
        AtomicInteger live = new AtomicInteger();
        AtomicInteger maxLive = new AtomicInteger();
        AtomicInteger opens = new AtomicInteger();
        CountDownLatch verifying = new CountDownLatch(1);
        AtomicLong coastEndsAt = new AtomicLong();
        Drive old = trackedDrive(live);
        Drive fresh = trackedDrive(live);
        when(fresh.getMotorSpeedValueAsRpm()).thenAnswer(invocation -> {
            coastEndsAt.compareAndSet(0, System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(coastMs));
            verifying.countDown();
            return System.nanoTime() < coastEndsAt.get() ? 400 : 0;
        });
        var opened = new ConcurrentLinkedQueue<>(List.of(old, fresh));
        DriveProvider tracking = () -> {
            opens.incrementAndGet();
            Drive next = opened.remove();
            maxLive.accumulateAndGet(live.incrementAndGet(), Math::max);
            return next;
        };
        var real = new FrequencyInverterDevice(tracking);
        var stopper = new MotorSafetyController(deviceServiceFor(real), tracking, new DriveLinkProperties(),
                coastMs + 2_000);
        real.connect();
        stopper.energize(d -> {
        });
        // The handle is gone, so both callers' tier 1 fails at once and both go to tier 2.
        real.invalidateConnection();

        var first = new AtomicReference<SafeStopResult>();
        Thread detector = Thread.ofPlatform().name("measurement-detector")
                .start(() -> first.set(stopper.safeStop("force sensor lost")));
        assertThat(verifying.await(5, TimeUnit.SECONDS)).isTrue();

        long started = System.nanoTime();
        SafeStopResult second = stopper.safeStop("runner cleanup");
        long tookMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        detector.join(10_000);

        assertThat(tookMs).as("tier 3 at the lock bound")
                .isGreaterThanOrEqualTo(FrequencyInverterDevice.RUN_EXCLUSIVE_WAIT_MS - 1)
                .isLessThan(SAFE_STOP_LOCK_ESCALATION_BOUND_MS);
        assertThat(second.tier()).isEqualTo(SafeStopResult.Tier.OPERATOR_ESCALATION);
        assertThat(second.detail())
                .contains("tier 2 (FRESH_HANDLE) not attempted")
                .contains("safe stop tier 2 on thread 'measurement-detector'");
        assertThat(detector.isAlive()).isFalse();
        assertThat(first.get().tier()).isEqualTo(SafeStopResult.Tier.FRESH_HANDLE);
        assertThat(first.get().verified()).isTrue();
        assertThat(stopper.getLastStopResult()).as("the run keeps the stop that ran").isSameAs(first.get());
        verify(fresh, times(1)).stop();
        assertThat(opens).as("the run's open and ONE tier-2 fresh handle").hasValue(2);
        assertThat(maxLive).as("sessions open on the drive at once").hasValue(1);
        assertThat(live).hasValue(0);
        real.disconnect();
    }

    // ---- the driver-owned gate (BL-121/BL-122) ---------------------------------------------------

    /**
     * A plugin built before device-api 1.5.0 answers CALLER_SERIALIZES. The deck does not serialize
     * drive calls, so it refuses to energize through such a plugin - an IllegalStateException naming
     * the jar and the contract, as a pre-1.2.0 plugin is refused for enableLinkLossStop - but still
     * stops it: a stale jar can de-energize, never run a test.
     */
    @Test
    void anUngatedPluginIsRefusedAtEnergizeButItsStopStillRuns() {
        UngatedDrive ungated = new UngatedDrive();
        drive = ungated;
        AtomicBoolean actionRan = new AtomicBoolean();

        assertThatThrownBy(() -> controller.energize(d -> actionRan.set(true)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("refusing to energize the motor")
                .hasMessageContaining(UngatedDrive.class.getName())
                .hasMessageContaining("loaded from "
                        + UngatedDrive.class.getProtectionDomain().getCodeSource().getLocation())
                .hasMessageContaining("CALLER_SERIALIZES")
                .hasMessageContaining("before device-api 1.5.0")
                .hasMessageContaining("device-api " + ContractVersion.VALUE)
                .hasMessageContaining("Rebuild the driver plugin");
        assertThat(actionRan).isFalse();
        assertThat(ungated.writes).as("nothing armed, nothing energized").isEmpty();

        SafeStopResult result = controller.safeStop("stale jar in drivers/");

        // The contract's two-call fallback stop, then the verification's reads.
        assertThat(ungated.writes).startsWith("setControlParameters(false,false,null,null,null)",
                "setSpeedReferenceValueAsRpm(0)");
        assertThat(result.verified()).isTrue();
        assertThat(result.motorWasEnergized()).isFalse();
        assertThatThrownBy(() -> controller.withDriveUnlessLatched(d -> d.setDirection(true)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("refusing to command the drive");
        assertThat(ungated.writes).doesNotContain("setDirection(true)");
    }

    /**
     * The run teardown's second-ramp reset (CyclicProgram#resetSecondRamp via RunLoop#disableSecondRamp)
     * reaches the drive through withDrive, also after a refused energize and beside the info poll. On
     * an ungated plugin that control-word write could cross a poll read, so it is refused like every
     * non-stop write and sends nothing; resetSecondRamp catches the refusal and logs it.
     */
    @Test
    void anUngatedPluginTakesNoSecondRampResetThroughWithDrive() {
        UngatedDrive ungated = new UngatedDrive();
        drive = ungated;

        assertThatThrownBy(() -> controller.withDrive(d -> d.setUseSecondRamp(false)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("refusing to command the drive")
                .hasMessageContaining("CALLER_SERIALIZES");
        assertThat(ungated.writes).isEmpty();
    }

    /** On a gated plugin the same reset goes out, latched or not. */
    @Test
    void aGatedPluginTakesTheSecondRampResetThroughWithDriveEvenWhenLatched() {
        controller.latchStop("run ended");

        controller.withDrive(d -> d.setUseSecondRamp(false));

        verify(drive).setUseSecondRamp(false);
    }

    /**
     * BL-122: a stop that latches between energize's latch check and its group's submit bumps the
     * drive's stop epoch first, so the group fenced on the epoch read before the check runs nothing
     * and energize throws. On the simulator's real gate, not a mock.
     */
    @Test
    @Timeout(value = 20, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void aStopBetweenTheLatchCheckAndTheSubmitRejectsTheEnergizeGroupWithNothingSent() {
        try (var bench = new SimBench()) {
            AtomicBoolean actionRan = new AtomicBoolean();
            bench.safety.afterLatchCheck(() -> bench.safety.safeStop("load cell lost mid-energize"));

            assertThatThrownBy(() -> bench.safety.energize(d -> {
                actionRan.set(true);
                d.setControlParameters(true, true, true, null, false);
            }))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("overtook")
                    .hasCauseInstanceOf(StaleStopEpochException.class);

            assertThat(actionRan).isFalse();
            // The stop and its verification, and not one write of the group.
            assertThat(bench.recording.golden()).containsExactly("h1 open", "h1 stop()");
            assertThat(bench.controlWord()).containsEntry("start", false).containsEntry("generalEnable", false);
        }
    }

    /** A stop that arrives while the energize group is open cuts it: its next call never goes out. */
    @Test
    @Timeout(value = 20, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void aStopDuringTheEnergizeGroupCutsItBeforeTheControlWord() throws InterruptedException {
        try (var bench = new SimBench()) {
            CountDownLatch stopped = new CountDownLatch(1);
            bench.recording.after(call -> call.method().equals("stop"), call -> stopped.countDown());
            AtomicReference<Thread> stopper = new AtomicReference<>();

            assertThatThrownBy(() -> bench.safety.energize(d -> {
                d.setSpeedReferenceValueAsRpm(100);
                stopper.set(Thread.ofPlatform().start(() -> bench.safety.safeStop("load cell lost mid-energize")));
                awaitQuietly(stopped);
                d.setControlParameters(true, true, true, null, false);
            }))
                    .isInstanceOf(IllegalStateException.class)
                    .hasCauseInstanceOf(StaleStopEpochException.class);
            stopper.get().join(10_000);
            assertThat(bench.safety.getLastStopResult().verified()).isTrue();

            // The control word was refused by the cut group and never reached the drive.
            assertThat(bench.recording.golden()).containsExactly("h1 open",
                    "h1 setActionInCaseOfCommunicationError(2)", "h1 enableLinkLossStop(PT2S)",
                    "h1 setSpeedReferenceValueAsRpm(100)", "h1 stop()",
                    "h1 setControlParameters(true,true,true,null,false) !StaleStopEpochException");
            assertThat(bench.controlWord()).containsEntry("start", false).containsEntry("generalEnable", false);
        }
    }

    /**
     * The fence RunLoop#turn and #runPhaseSpeed go through: a reversal or setpoint whose group a stop
     * overtook never reaches the drive after that stop.
     */
    @Test
    @Timeout(value = 20, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void aSetpointOvertakenByAStopIsDroppedNotSentAfterIt() {
        try (var bench = new SimBench()) {
            bench.safety.afterLatchCheck(() -> bench.safety.safeStop("crossing raced a loss"));

            boolean commanded = bench.safety.withDriveUnlessLatched(d -> {
                d.setDirection(false);
                d.setSpeedReferenceValueAsRpm(266);
            });

            assertThat(commanded).isFalse();
            assertThat(bench.recording.golden()).containsExactly("h1 open", "h1 stop()");

            // Once latched, the check itself refuses: nothing is submitted at all.
            bench.safety.afterLatchCheck(() -> {
            });
            assertThat(bench.safety.withDriveUnlessLatched(d -> d.setDirection(true))).isFalse();
            assertThat(bench.recording.golden()).containsExactly("h1 open", "h1 stop()");
        }
    }

    /** One controller over a real device and the simulated drive, recorded. */
    private static final class SimBench implements AutoCloseable {
        final SimulatedFaultSwitches faults = new SimulatedFaultSwitches();
        final SimulatedBench plant = new SimulatedBench(new SimulatedBenchProperties(), faults);
        final RecordingDriveProvider recording = new RecordingDriveProvider(new SimulatedDriveProvider(plant, faults));
        final FrequencyInverterDevice device = new FrequencyInverterDevice(recording);
        final MotorSafetyController safety =
                new MotorSafetyController(deviceServiceFor(device), recording, new DriveLinkProperties(), 300);

        SimBench() {
            device.connect();
        }

        /** The plant's control word, read through a handle of its own outside the recorder. */
        Map<String, Boolean> controlWord() {
            Drive direct = new SimulatedDriveProvider(plant, faults).open();
            try {
                return direct.getControlParameters();
            } finally {
                direct.close();
            }
        }

        @Override
        public void close() {
            device.disconnect();
        }
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            assertThat(latch.await(5, TimeUnit.SECONDS)).as("latch released").isTrue();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    /**
     * A plugin built before device-api 1.5.0: no gating(), no stop() of its own, so both are the
     * contract's defaults. A real class rather than a mock, so it has a code source to name.
     */
    private static final class UngatedDrive implements Drive {
        final List<String> writes = new java.util.concurrent.CopyOnWriteArrayList<>();

        @Override
        public void setControlParameters(Boolean start, Boolean generalEnable, Boolean directionIsForward,
                                         Boolean localRemote, Boolean useSecondRamp) {
            writes.add("setControlParameters(" + start + "," + generalEnable + "," + directionIsForward + ","
                    + localRemote + "," + useSecondRamp + ")");
        }

        @Override
        public Map<String, Boolean> getControlParameters() {
            return DE_ENERGIZED;
        }

        @Override
        public void setStart(boolean start) {
            writes.add("setStart(" + start + ")");
        }

        @Override
        public void setGeneralEnable(boolean generalEnable) {
            writes.add("setGeneralEnable(" + generalEnable + ")");
        }

        @Override
        public void setDirection(boolean directionIsForward) {
            writes.add("setDirection(" + directionIsForward + ")");
        }

        @Override
        public boolean getDirection() {
            return true;
        }

        @Override
        public void setUseSecondRamp(boolean useSecondRamp) {
            writes.add("setUseSecondRamp(" + useSecondRamp + ")");
        }

        @Override
        public void setSecondSpeedRampTime(int accelerationRampTime, int decelerationRampTime) {
            writes.add("setSecondSpeedRampTime");
        }

        @Override
        public void setSpeedReferenceValueAsRpm(int rpm) {
            writes.add("setSpeedReferenceValueAsRpm(" + rpm + ")");
        }

        @Override
        public int getMotorSpeedValueAsRpm() {
            return 0;
        }

        @Override
        public Map<String, Integer> getMotorData() {
            return Map.of("speed", 0, "current", 0, "voltage", 0, "torque", 0);
        }

        @Override
        public void setActionInCaseOfCommunicationError(int action) {
            writes.add("setActionInCaseOfCommunicationError(" + action + ")");
        }

        @Override
        public void close() {
        }
    }

    /** A quiet drive whose close() releases one session from {@code live}, once. */
    private static Drive trackedDrive(AtomicInteger live) {
        Drive tracked = quietDrive();
        AtomicBoolean closed = new AtomicBoolean();
        doAnswer(invocation -> {
            if (!closed.getAndSet(true)) {
                live.decrementAndGet();
            }
            return null;
        }).when(tracked).close();
        return tracked;
    }
}
