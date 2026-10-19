package ch.rupfizupfi.deck.testrunner;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ShutdownSafeStopTest {

    private final MotorSafetyController motorSafetyController = mock(MotorSafetyController.class);

    @Test
    void anOpenDriveHandleIsSafeStoppedExactlyOnce() {
        when(motorSafetyController.isDriveAvailable()).thenReturn(true);
        when(motorSafetyController.safeStop(anyString())).thenReturn(
                new SafeStopResult(SafeStopResult.Tier.EXISTING_HANDLE, true, true, true, 0, "standstill"));
        var lifecycle = new ShutdownSafeStop(motorSafetyController);
        lifecycle.start();

        lifecycle.stop();
        // stop(Runnable) and a second close must not repeat the stop against the hardware.
        lifecycle.stop(() -> {
        });

        verify(motorSafetyController, times(1)).safeStop(ShutdownSafeStop.REASON);
        assertThat(lifecycle.isRunning()).isFalse();
    }

    @Test
    void noDriveHandleOnlyLatchesWithoutTouchingTheHardware() {
        // Nothing can be energized without a handle; the latch stops a run in setup from energizing.
        when(motorSafetyController.isDriveAvailable()).thenReturn(false);
        var lifecycle = new ShutdownSafeStop(motorSafetyController);
        lifecycle.start();

        lifecycle.stop();

        verify(motorSafetyController).latchStop(ShutdownSafeStop.REASON);
        verify(motorSafetyController, never()).safeStop(anyString());
    }

    @Test
    @Timeout(10)
    void aWedgedStopCannotHoldShutdownPastItsBound() throws Exception {
        when(motorSafetyController.isDriveAvailable()).thenReturn(true);
        CountDownLatch never = new CountDownLatch(1);
        when(motorSafetyController.safeStop(anyString())).thenAnswer(invocation -> {
            never.await(1, TimeUnit.MINUTES);
            return null;
        });
        var lifecycle = new ShutdownSafeStop(motorSafetyController, 200);
        lifecycle.start();

        long started = System.nanoTime();
        lifecycle.stop();
        long tookMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

        assertThat(tookMs).isBetween(150L, 5000L);
        verify(motorSafetyController).safeStop(ShutdownSafeStop.REASON);
        never.countDown();
    }

    @Test
    void itStopsBeforeEveryOtherLifecycleBean() {
        var lifecycle = new ShutdownSafeStop(motorSafetyController);

        assertThat(lifecycle.getPhase()).isEqualTo(Integer.MAX_VALUE);
        assertThat(lifecycle.isAutoStartup()).isTrue();
    }
}
