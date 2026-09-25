package ch.rupfizupfi.deck.device.loadcell;

import ch.rupfizupfi.deck.device.api.Measurement;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class ForceBroadcasterTest {

    private static final String TOPIC = "/topic/load-cell";

    private final SimpMessagingTemplate template = mock(SimpMessagingTemplate.class);
    private final ForceBroadcaster broadcaster = new ForceBroadcaster(template);
    // The payload parameter is Object; capture() and any() are cast to it so the overload binds.
    private final ArgumentCaptor<Object> sent = ArgumentCaptor.forClass(Object.class);

    @Test
    void aBatchYoungerThanTheFlushIntervalStaysBuffered() {
        broadcaster.update(List.of(new Measurement(1f, System.currentTimeMillis())));

        verify(template, never()).convertAndSend(eq(TOPIC), (Object) any());
    }

    @Test
    void flushSendsTheBufferedTailExactlyOnce() {
        var measurement = new Measurement(1f, System.currentTimeMillis());
        broadcaster.update(List.of(measurement));

        broadcaster.flush();
        broadcaster.flush();

        verify(template).convertAndSend(eq(TOPIC), (Object) sent.capture());
        assertThat(sent.getValue()).isEqualTo(List.of(measurement));
    }

    @Test
    void aBatchOlderThanTheFlushIntervalIsSentFromUpdate() {
        var measurement = new Measurement(2f, System.currentTimeMillis() - 1000);
        broadcaster.update(List.of(measurement));

        verify(template).convertAndSend(eq(TOPIC), (Object) sent.capture());
        assertThat(sent.getValue()).isEqualTo(List.of(measurement));
    }
}
