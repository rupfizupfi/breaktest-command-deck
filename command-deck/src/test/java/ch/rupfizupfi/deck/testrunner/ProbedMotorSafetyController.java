package ch.rupfizupfi.deck.testrunner;

import ch.rupfizupfi.deck.device.DeviceService;
import ch.rupfizupfi.deck.device.api.DriveProvider;
import ch.rupfizupfi.deck.testrunner.run.RunLoop;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The run's {@link MotorSafetyController}, built through its package-private constructor for a
 * short verify deadline, with two observation points and no change to what it does:
 * <ul>
 *     <li>{@link #onLatch} hears every {@link #latchStop} once the latch is set, so a scenario can
 *     release a held call exactly after an operator Stop has latched;</li>
 *     <li>{@link #pauseResumeAtStopRead} parks the run loop inside its RESUMING entry action at
 *     its {@link #getLastStopResult()} read - after the fresh-data proof, before the latch is
 *     cleared - so a scenario can land a Stop or a second loss on an in-flight resume at a known
 *     point.</li>
 * </ul>
 */
final class ProbedMotorSafetyController extends MotorSafetyController {

    /**
     * The resume's entry action, which runs on the run loop since OQ-64 step 6. Matched on the
     * stack rather than the thread name: the loop reads the stop result elsewhere too (closing a
     * gap), and only the resume's read may park.
     */
    private static final String RESUME_FRAME = "resume";

    /** A parked call and the latch that releases it. */
    static final class Pause {
        private final CountDownLatch reached = new CountDownLatch(1);
        private final CountDownLatch released = new CountDownLatch(1);

        boolean awaitReached(long timeoutMs) throws InterruptedException {
            return reached.await(timeoutMs, TimeUnit.MILLISECONDS);
        }

        void release() {
            released.countDown();
        }

        /** Bounded, so a scenario that never releases fails its own assertions instead of hanging. */
        private void park() {
            reached.countDown();
            try {
                released.await(20, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private final List<Runnable> latchListeners = new CopyOnWriteArrayList<>();
    private final AtomicReference<Pause> resumePause = new AtomicReference<>();

    ProbedMotorSafetyController(DeviceService deviceService, DriveProvider driveProvider,
                                DriveLinkProperties driveLinkProperties, long verifyDeadlineMs) {
        super(deviceService, driveProvider, driveLinkProperties, verifyDeadlineMs);
    }

    void reset() {
        latchListeners.clear();
        Pause pending = resumePause.getAndSet(null);
        if (pending != null) {
            pending.release();
        }
    }

    void onLatch(Runnable listener) {
        latchListeners.add(listener);
    }

    /** Parks the next stop-result read made by the run loop's resume entry action. */
    Pause pauseResumeAtStopRead() {
        Pause pause = new Pause();
        resumePause.set(pause);
        return pause;
    }

    @Override
    public void latchStop(String reason) {
        super.latchStop(reason);
        for (Runnable listener : latchListeners) {
            listener.run();
        }
    }

    @Override
    public SafeStopResult getLastStopResult() {
        if (inResumeEntry()) {
            Pause pause = resumePause.getAndSet(null);
            if (pause != null) {
                pause.park();
            }
        }
        return super.getLastStopResult();
    }

    private static boolean inResumeEntry() {
        return StackWalker.getInstance().walk(frames -> frames.anyMatch(frame ->
                frame.getClassName().equals(RunLoop.class.getName()) && frame.getMethodName().equals(RESUME_FRAME)));
    }
}
