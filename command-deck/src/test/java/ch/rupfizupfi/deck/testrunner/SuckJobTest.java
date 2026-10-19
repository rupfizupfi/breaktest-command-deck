package ch.rupfizupfi.deck.testrunner;

import ch.rupfizupfi.deck.api.services.SuckService;
import ch.rupfizupfi.deck.device.relayswitch.RelaySwitch;
import ch.rupfizupfi.deck.device.relayswitch.RelaySwitchProvider;
import ch.rupfizupfi.deck.device.simulated.SimulatedRelaySwitch;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.mockito.InOrder;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SuckJobTest {

    private final RelaySwitch relay = mock(RelaySwitch.class);
    /** What the operator was shown: the job's notice sink, {@code /topic/logs} in production. */
    private final List<String> published = new CopyOnWriteArrayList<>();

    private SuckJob job(int seconds, SuckService service) {
        return new SuckJob(seconds, service, published::add, Duration.ofMillis(1));
    }

    /** Counts the port openings, so a second owner of the relay is visible. */
    private static class CountingProvider implements RelaySwitchProvider {
        private final RelaySwitch relay;
        private int openCount;

        CountingProvider(RelaySwitch relay) {
            this.relay = relay;
        }

        @Override
        public RelaySwitch open() {
            openCount++;
            return relay;
        }
    }

    @Test
    @Timeout(5)
    void aRelayThatDoesNotSwitchOnSkipsTheHold() {
        when(relay.connect()).thenReturn(false);
        SuckJob job = job(2, new SuckService(new CountingProvider(relay)));

        long startedAt = System.nanoTime();
        job.suck();
        long elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000;

        assertThat(elapsedMillis).isLessThan(500);
        verify(relay, never()).enableRelay1();
        verify(relay).disconnect();
    }

    @Test
    @Timeout(10)
    void anEnergizedRelayIsReleasedAfterTheHold() {
        when(relay.connect()).thenReturn(true);
        when(relay.enableRelay1()).thenReturn(true);
        SuckJob job = job(1, new SuckService(new CountingProvider(relay)));

        job.suck();

        InOrder order = inOrder(relay);
        order.verify(relay).enableRelay1();
        order.verify(relay).disableRelay1();
        order.verify(relay).disconnect();
    }

    @Test
    @Timeout(10)
    void aRefusedOffCommandStillDisconnects() {
        when(relay.connect()).thenReturn(true);
        when(relay.enableRelay1()).thenReturn(true);
        when(relay.disableRelay1()).thenReturn(false);
        SuckJob job = job(1, new SuckService(new CountingProvider(relay)));

        assertThatCode(job::suck).doesNotThrowAnyException();

        InOrder order = inOrder(relay);
        for (int attempt = 0; attempt < SuckJob.MAX_OFF_ATTEMPTS; attempt++) {
            order.verify(relay).disableRelay1();
            order.verify(relay).disconnect();
        }
    }

    @Test
    @Timeout(10)
    void aRefusedOffCommandIsRetriedAndASuccessRaisesNoAlarm() {
        SuckService service = mock(SuckService.class);
        when(service.enable()).thenReturn(true);
        when(service.disable()).thenReturn(false, false, true);

        job(0, service).suck();

        verify(service, times(3)).disable();
        assertThat(published).isEmpty();
    }

    @Test
    @Timeout(10)
    void anOffCommandRefusedEveryTimeAlarmsTheOperator() {
        SuckService service = mock(SuckService.class);
        when(service.enable()).thenReturn(true);
        when(service.disable()).thenReturn(false);

        job(0, service).suck();

        verify(service, times(SuckJob.MAX_OFF_ATTEMPTS)).disable();
        assertThat(published).containsExactly(
                "ALARM: vacuum relay 1 did not accept the off command (3 attempts) - it may still be"
                        + " energized; switch the vacuum off on the dashboard or at the relay board");
    }

    /** An interrupt skips the waits, not the attempts: the off command still goes out every time. */
    @Test
    @Timeout(10)
    void anInterruptedRetryStillSendsEveryAttemptAndAlarms() throws InterruptedException {
        SuckService service = mock(SuckService.class);
        when(service.enable()).thenReturn(true);
        CountDownLatch firstRefusal = new CountDownLatch(1);
        when(service.disable()).thenAnswer(invocation -> {
            firstRefusal.countDown();
            return false;
        });
        AtomicBoolean interruptKept = new AtomicBoolean();
        SuckJob slowRetry = new SuckJob(0, service, published::add, Duration.ofSeconds(30));
        Thread thread = new Thread(() -> {
            slowRetry.suck();
            interruptKept.set(Thread.currentThread().isInterrupted());
        });

        thread.start();
        assertThat(firstRefusal.await(5, TimeUnit.SECONDS)).isTrue();
        thread.interrupt();
        thread.join(5_000);

        assertThat(thread.isAlive()).isFalse();
        verify(service, times(SuckJob.MAX_OFF_ATTEMPTS)).disable();
        assertThat(interruptKept).isTrue();
        assertThat(published).containsExactly(SuckJob.offRefusedAlarm(SuckJob.MAX_OFF_ATTEMPTS));
    }

    /** A port that throws on reopen is a refusal too; the thread must live to raise the alarm. */
    @Test
    @Timeout(10)
    void anOffCommandThatThrowsEveryTimeStillAlarms() {
        SuckService service = mock(SuckService.class);
        when(service.enable()).thenReturn(true);
        when(service.disable()).thenThrow(new IllegalStateException("port vanished"));

        assertThatCode(job(0, service)::suck).doesNotThrowAnyException();

        verify(service, times(SuckJob.MAX_OFF_ATTEMPTS)).disable();
        assertThat(published).containsExactly(SuckJob.offRefusedAlarm(3));
    }

    @Test
    @Timeout(10)
    void aBrokenNoticeSinkDoesNotKillTheVacuumThread() {
        SuckService service = mock(SuckService.class);
        when(service.enable()).thenReturn(true);
        when(service.disable()).thenReturn(false);
        SuckJob job = new SuckJob(0, service, line -> {
            throw new IllegalStateException("broker down");
        }, Duration.ofMillis(1));

        assertThatCode(job::suck).doesNotThrowAnyException();
    }

    @Test
    @Timeout(10)
    void aDashboardHoldAndTheJobShareTheOnePortHandle() {
        SimulatedRelaySwitch shared = new SimulatedRelaySwitch();
        CountingProvider provider = new CountingProvider(shared);
        SuckService service = new SuckService(provider);
        assertThat(service.enable()).isTrue();

        job(1, service).suck();

        assertThat(shared.isEnergized()).isFalse();
        assertThat(shared.isConnected()).isFalse();
        assertThat(provider.openCount).isEqualTo(1);
    }
}
