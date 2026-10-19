package ch.rupfizupfi.deck.testrunner;

import ch.rupfizupfi.deck.device.api.ControlSource;
import ch.rupfizupfi.deck.device.api.Drive;
import ch.rupfizupfi.deck.device.api.DriveAsync;
import ch.rupfizupfi.deck.device.api.DriveGating;
import ch.rupfizupfi.deck.device.api.DriveProvider;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * Decorates the simulator's {@link DriveProvider} and records every {@link Drive} call, with its
 * arguments, in the order the drive received them, labelled by handle ({@code h1}, {@code h2}, ...
 * in open order since the last {@link #reset()}). A test-scope seam over the public
 * {@code device-api} contract only: the simulated drive behind it is untouched.
 * <p>
 * The simulated drive gates its calls (one I/O thread per handle) and the deck's calls that must stay
 * ordered come from one thread or one linked group, so the recorded order of writes is the order the
 * drive saw. The golden form ({@link #golden()}) keeps writes and the open/close lifecycle and drops
 * reads, whose count is clock-driven (stop verification polls, info poll ticks). The simulated
 * driver's own keep-alive reads never pass through here.
 * <p>
 * Hooks let a scenario act at an exact point of the write sequence — arm a fault right after a
 * reversal, or hold a call until the operator command it races has landed — instead of guessing a
 * moment with a sleep.
 */
final class RecordingDriveProvider implements DriveProvider {

    /** Calls whose repetition count depends on the clock, never on the code path. */
    private static final Set<String> READS = Set.of("getControlParameters", "readControlSource",
            "getDirection", "getMotorSpeedValueAsRpm", "getMotorData");

    /**
     * One call as the drive received it.
     *
     * @param threw the simple name of what the call threw, null when it returned
     */
    record DriveCall(long seq, String handle, String method, String args, String threw, String thread,
                     long nanos, long epochMillis) {

        boolean isRead() {
            return READS.contains(method);
        }

        boolean isLifecycle() {
            return method.equals("open") || method.equals("close");
        }

        boolean isWrite() {
            return !isRead() && !isLifecycle();
        }

        String render() {
            return handle + " " + method + (method.equals("open") || method.equals("close") ? "" : "(" + args + ")")
                    + (threw == null ? "" : " !" + threw);
        }
    }

    /** A one-shot action at a call; {@code before} runs ahead of the delegate, otherwise after it. */
    private record Hook(Predicate<DriveCall> when, boolean before, Consumer<DriveCall> action) {
    }

    private final DriveProvider delegate;
    private final List<DriveCall> calls = new ArrayList<>();
    private final List<Hook> hooks = new CopyOnWriteArrayList<>();
    private final Object lock = new Object();
    private long seq;
    private int handles;

    RecordingDriveProvider(DriveProvider delegate) {
        this.delegate = delegate;
    }

    /** Forgets every call and hook; handles opened afterwards are numbered from h1 again. */
    void reset() {
        synchronized (lock) {
            calls.clear();
            seq = 0;
            handles = 0;
        }
        hooks.clear();
    }

    List<DriveCall> calls() {
        synchronized (lock) {
            return List.copyOf(calls);
        }
    }

    /**
     * Writes and lifecycle in order, reads dropped, and a run of identical lines collapsed to one
     * marked {@code [repeated]}: the stop verification re-sends its de-energize write on every poll
     * until the read-back confirms it, and a dead drive never confirms, so only "once" versus "more
     * than once" is a property of the code path.
     */
    List<String> golden() {
        List<String> lines = new ArrayList<>();
        String previous = null;
        int run = 0;
        for (DriveCall call : calls()) {
            if (call.isRead()) {
                continue;
            }
            String line = call.render();
            if (line.equals(previous)) {
                run++;
                continue;
            }
            if (run > 0) {
                lines.set(lines.size() - 1, previous + " [repeated]");
            }
            lines.add(line);
            previous = line;
            run = 0;
        }
        if (run > 0) {
            lines.set(lines.size() - 1, previous + " [repeated]");
        }
        return lines;
    }

    /** Runs {@code action} once, on the calling thread, right after the first call matching {@code when}. */
    void after(Predicate<DriveCall> when, Consumer<DriveCall> action) {
        hooks.add(new Hook(when, false, action));
    }

    /** Runs {@code action} once, on the calling thread, before the first call matching {@code when} reaches the drive. */
    void before(Predicate<DriveCall> when, Consumer<DriveCall> action) {
        hooks.add(new Hook(when, true, action));
    }

    @Override
    public Drive open() {
        String handle;
        synchronized (lock) {
            handle = "h" + (++handles);
        }
        Drive drive;
        try {
            drive = delegate.open();
        } catch (RuntimeException | Error e) {
            record(handle, "open", "", e);
            throw e;
        }
        record(handle, "open", "", null);
        return new RecordingDrive(handle, drive);
    }

    private DriveCall record(String handle, String method, String args, Throwable threw) {
        synchronized (lock) {
            DriveCall call = new DriveCall(++seq, handle, method, args,
                    threw == null ? null : threw.getClass().getSimpleName(), Thread.currentThread().getName(),
                    System.nanoTime(), System.currentTimeMillis());
            calls.add(call);
            return call;
        }
    }

    private void fire(DriveCall probe, boolean before) {
        for (Hook hook : hooks) {
            if (hook.before() == before && hook.when().test(probe) && hooks.remove(hook)) {
                hook.action().accept(probe);
            }
        }
    }

    private static String args(Object... values) {
        return Arrays.stream(values).map(String::valueOf).collect(Collectors.joining(","));
    }

    private final class RecordingDrive implements Drive {
        private final String handle;
        private final Drive drive;

        RecordingDrive(String handle, Drive drive) {
            this.handle = handle;
            this.drive = drive;
        }

        private <T> T call(String method, String args, Supplier<T> body) {
            DriveCall probe = new DriveCall(0, handle, method, args, null, Thread.currentThread().getName(),
                    System.nanoTime(), System.currentTimeMillis());
            fire(probe, true);
            T result;
            try {
                result = body.get();
            } catch (RuntimeException | Error e) {
                record(handle, method, args, e);
                throw e;
            }
            DriveCall recorded = record(handle, method, args, null);
            fire(recorded, false);
            return result;
        }

        private void run(String method, String args, Runnable body) {
            call(method, args, () -> {
                body.run();
                return null;
            });
        }

        @Override
        public void setControlParameters(Boolean start, Boolean generalEnable, Boolean directionIsForward,
                                         Boolean localRemote, Boolean useSecondRamp) {
            run("setControlParameters", args(start, generalEnable, directionIsForward, localRemote, useSecondRamp),
                    () -> drive.setControlParameters(start, generalEnable, directionIsForward, localRemote,
                            useSecondRamp));
        }

        @Override
        public Map<String, Boolean> getControlParameters() {
            return call("getControlParameters", "", drive::getControlParameters);
        }

        @Override
        public ControlSource readControlSource() {
            return call("readControlSource", "", drive::readControlSource);
        }

        @Override
        public void setStart(boolean start) {
            run("setStart", args(start), () -> drive.setStart(start));
        }

        @Override
        public void setGeneralEnable(boolean generalEnable) {
            run("setGeneralEnable", args(generalEnable), () -> drive.setGeneralEnable(generalEnable));
        }

        @Override
        public void setDirection(boolean directionIsForward) {
            run("setDirection", args(directionIsForward), () -> drive.setDirection(directionIsForward));
        }

        @Override
        public boolean getDirection() {
            return call("getDirection", "", drive::getDirection);
        }

        @Override
        public void setUseSecondRamp(boolean useSecondRamp) {
            run("setUseSecondRamp", args(useSecondRamp), () -> drive.setUseSecondRamp(useSecondRamp));
        }

        @Override
        public void setSecondSpeedRampTime(int accelerationRampTime, int decelerationRampTime) {
            run("setSecondSpeedRampTime", args(accelerationRampTime, decelerationRampTime),
                    () -> drive.setSecondSpeedRampTime(accelerationRampTime, decelerationRampTime));
        }

        @Override
        public void setSpeedReferenceValueAsRpm(int rpm) {
            run("setSpeedReferenceValueAsRpm", args(rpm), () -> drive.setSpeedReferenceValueAsRpm(rpm));
        }

        @Override
        public int getMotorSpeedValueAsRpm() {
            return call("getMotorSpeedValueAsRpm", "", drive::getMotorSpeedValueAsRpm);
        }

        @Override
        public Map<String, Integer> getMotorData() {
            return call("getMotorData", "", drive::getMotorData);
        }

        @Override
        public void setActionInCaseOfCommunicationError(int action) {
            run("setActionInCaseOfCommunicationError", args(action),
                    () -> drive.setActionInCaseOfCommunicationError(action));
        }

        @Override
        public void enableLinkLossStop(Duration timeout) {
            run("enableLinkLossStop", args(timeout), () -> drive.enableLinkLossStop(timeout));
        }

        @Override
        public void stop() {
            run("stop", "", drive::stop);
        }

        @Override
        public DriveGating gating() {
            return drive.gating();
        }

        @Override
        public long stopEpoch() {
            return drive.stopEpoch();
        }

        /** Not a call of its own: the group's calls are recorded as the drive receives them. */
        @Override
        public void runLinked(long expectedEpoch, Consumer<Drive> body) {
            drive.runLinked(expectedEpoch, linked -> body.accept(new RecordingDrive(handle, linked)));
        }

        @Override
        public DriveAsync async() {
            return drive.async();
        }

        @Override
        public void close() {
            run("close", "", drive::close);
        }

        @Override
        public String toString() {
            return "RecordingDrive[" + handle + " -> " + Objects.toString(drive) + "]";
        }
    }
}
