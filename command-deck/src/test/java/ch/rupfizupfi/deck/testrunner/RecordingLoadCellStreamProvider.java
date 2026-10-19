package ch.rupfizupfi.deck.testrunner;

import ch.rupfizupfi.deck.device.api.LoadCellStream;
import ch.rupfizupfi.deck.device.api.LoadCellStreamProvider;
import ch.rupfizupfi.deck.device.api.Measurement;
import ch.rupfizupfi.deck.device.api.StreamFailure;
import ch.rupfizupfi.deck.device.loadcell.SessionAwareStreamProvider;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Decorates the simulator's stream provider and records every sample the bench delivered to the
 * deck, in delivery order, so a scenario can hold the force CSV against it. Implements
 * {@link SessionAwareStreamProvider} like the simulator does, so a mid-run reconnect still keeps the
 * mounted specimen.
 * <p>
 * {@link #injectOnce} is the one fault this adds: one extra element at the head of the next
 * non-empty batch. A {@code null} is a driver bug that kills the measurement loop rather than the
 * sensor - the only way to reach {@code MeasurementLoop#run}'s {@code catch (Throwable)} without a
 * production seam; a single high reading is a force-ceiling crossing that no plausibility vote can
 * claim, since one sample is one vote of the three needed.
 */
final class RecordingLoadCellStreamProvider implements LoadCellStreamProvider, SessionAwareStreamProvider {

    /** One delivered sample and when the deck's reader drained it. */
    record Delivered(Measurement measurement, long nanos) {
    }

    private final SessionAwareStreamProvider delegate;
    private final List<Delivered> delivered = new ArrayList<>();
    /** A one-element box, so a pending {@code null} injection differs from none. */
    private final AtomicReference<Measurement[]> injection = new AtomicReference<>();
    /** Runs once, on the reader thread, after the first delivered sample above its force; null when none. */
    private final AtomicReference<ForceHook> forceHook = new AtomicReference<>();

    private record ForceHook(float aboveNewton, Runnable action) {
    }

    RecordingLoadCellStreamProvider(SessionAwareStreamProvider delegate) {
        this.delegate = delegate;
    }

    void reset() {
        synchronized (delivered) {
            delivered.clear();
        }
        injection.set(null);
        forceHook.set(null);
    }

    /**
     * Runs {@code action} once, on the reader thread, as soon as a delivered sample exceeds
     * {@code newton}: where a scenario needs a fault to land at a force rather than at a drive write.
     */
    void onceAbove(float newton, Runnable action) {
        forceHook.set(new ForceHook(newton, action));
    }

    List<Delivered> delivered() {
        synchronized (delivered) {
            return List.copyOf(delivered);
        }
    }

    /** {@code measurement} may be null; it is recorded as delivered unless it is. */
    void injectOnce(Measurement measurement) {
        injection.set(new Measurement[]{measurement});
    }

    @Override
    public LoadCellStream open() {
        return open(false);
    }

    @Override
    public LoadCellStream open(boolean reconnect) {
        return new RecordingStream(delegate.open(reconnect));
    }

    private final class RecordingStream implements LoadCellStream {
        private final LoadCellStream stream;

        RecordingStream(LoadCellStream stream) {
            this.stream = stream;
        }

        @Override
        public void startReading() {
            stream.startReading();
        }

        @Override
        public void stopReading() {
            stream.stopReading();
        }

        @Override
        public List<Measurement> getNextValues() {
            List<Measurement> values = stream.getNextValues();
            if (values.isEmpty()) {
                return values;
            }
            Measurement[] injected = injection.getAndSet(null);
            if (injected != null) {
                List<Measurement> extended = new ArrayList<>(values.size() + 1);
                extended.add(injected[0]);
                extended.addAll(values);
                values = extended;
            }
            long now = System.nanoTime();
            synchronized (delivered) {
                for (Measurement measurement : values) {
                    if (measurement != null) {
                        delivered.add(new Delivered(measurement, now));
                    }
                }
            }
            ForceHook hook = forceHook.get();
            if (hook != null && values.stream().anyMatch(m -> m != null && m.force() > hook.aboveNewton())
                    && forceHook.compareAndSet(hook, null)) {
                hook.action().run();
            }
            return values;
        }

        @Override
        public boolean isReading() {
            return stream.isReading();
        }

        @Override
        public StreamFailure lastError() {
            return stream.lastError();
        }

        @Override
        public long droppedSampleCount() {
            return stream.droppedSampleCount();
        }
    }
}
