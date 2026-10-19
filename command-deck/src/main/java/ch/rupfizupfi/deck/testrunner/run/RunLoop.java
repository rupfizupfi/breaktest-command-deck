package ch.rupfizupfi.deck.testrunner.run;

import ch.rupfizupfi.deck.testrunner.RecoveryGates;
import ch.rupfizupfi.deck.testrunner.SafeStopResult;
import ch.rupfizupfi.deck.testrunner.measure.SensorReconnector;
import ch.rupfizupfi.deck.testrunner.TestState;
import ch.rupfizupfi.deck.testrunner.TestStateMachine;
import ch.rupfizupfi.deck.device.api.Drive;
import ch.rupfizupfi.deck.testrunner.measure.EmergencyStop;
import ch.rupfizupfi.deck.testrunner.measure.ForceLimits;
import ch.rupfizupfi.deck.testrunner.measure.RunEventSink;
import ch.rupfizupfi.deck.testrunner.program.DriveSetup;
import ch.rupfizupfi.deck.testrunner.program.Limits;
import ch.rupfizupfi.deck.testrunner.program.Ramp;
import ch.rupfizupfi.deck.testrunner.program.ResumePolicy;
import ch.rupfizupfi.deck.testrunner.program.TestProgram;
import ch.rupfizupfi.deck.testrunner.run.RunEvent.Abort;
import ch.rupfizupfi.deck.testrunner.run.RunEvent.CeilingTripped;
import ch.rupfizupfi.deck.testrunner.run.RunEvent.LimitCrossed;
import ch.rupfizupfi.deck.testrunner.run.RunEvent.OperatorStop;
import ch.rupfizupfi.deck.testrunner.run.RunEvent.PersistenceFailed;
import ch.rupfizupfi.deck.testrunner.run.RunEvent.Recovered;
import ch.rupfizupfi.deck.testrunner.run.RunEvent.ResumeAccepted;
import ch.rupfizupfi.deck.testrunner.run.RunEvent.SensorLost;
import ch.rupfizupfi.deck.testrunner.run.RunEvent.Tick;
import ch.rupfizupfi.deck.testrunner.run.RunEvent.TimedTurn;
import ch.rupfizupfi.deck.testrunner.run.RunEvent.WatchdogFailed;
import jakarta.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Iterator;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * One run's control thread: runs the STARTING entry action, then consumes {@link RunEvent}s until
 * the run ends, then runs the terminal entry action exactly once (OQ-64 steps 5 to 7,
 * {@code doc/dev/backend/testrunner-architecture.md}). It drives the run's {@link TestProgram}
 * and is the {@link RunControl} that program acts through.
 * <p>
 * The loop is the only thread that runs program code, entry actions and the run's teardown, and the
 * only writer of the incident's per-run fields. State is committed where the cause is seen: an
 * operator command on its request thread, a detector trip or a sensor loss on the measurement
 * thread in {@link #accept}, after that thread de-energized. The event then tells the loop to run
 * the entry action. A transition the loop asks for is refused once the run is terminal, which is
 * how a Stop or Abort committed elsewhere wins over whatever the loop computes afterwards.
 * <p>
 * The run ends when the loop handles an ending event (a finish verdict, Stop, Abort, a trip) or
 * when the loop itself committed a terminal state (a failed resume or hold entry, the hold
 * expiry). A terminal state committed by another thread always has its event behind it, and the
 * loop waits for that event, so the event's run-log lines and its stop keep their order.
 */
public final class RunLoop implements RunEventSink, RunControl, Runnable {
    private static final Logger logger = LoggerFactory.getLogger(RunLoop.class);

    /** Actor and reason of the abort the loop makes on its own when a hold is left unattended. */
    public static final String SAFE_HOLD_TIMEOUT_ACTOR = "safe-hold timeout";

    /** Fresh-data proof demanded before the drive is re-enabled for a resume. */
    static final long RESUME_FRESH_DATA_TIMEOUT_MS = 2000;
    /**
     * Same age the watchdog itself trips on, so a resume can never pass on a stream the watchdog
     * would immediately reject.
     */
    static final long RESUME_DATA_FLOW_MAX_AGE_MS = 250;
    /** Re-broadcast period during an incident, so a browser that reconnects mid-incident converges. */
    static final long INCIDENT_REBROADCAST_MS = 2000;

    /**
     * The run's devices as the loop uses them: the measurement loop, the reconnector and the drive
     * connection. {@code RunDevices} in production. Program code never sees this; it acts through
     * {@link RunControl}.
     */
    public interface Devices {
        /** Creates and starts the measurement loop on {@code limits}, reporting to {@code sink}. */
        void startMeasurement(ForceLimits limits, RunEventSink sink);

        /**
         * Returns once the device delivered a fresh sample <b>and</b> the measurement loop processed
         * one, so the watchdog is armed before anything is energized (I10).
         *
         * @throws IllegalStateException when either wait times out
         */
        void awaitFirstSample();

        /** Opens the drive for this run; balanced by {@link #destroy()}. */
        void connectDrive();

        RunControl.Envelope envelope();

        void resetEnvelopeMin(float value);

        void resetEnvelopeMax(float value);

        /** Last force the watchdog saw, {@link Float#NaN} when none. */
        float lastForce();

        /** The measurement loop's turn gate, for timed turns. */
        RunEventSink timedTurns();

        /**
         * Starts the reconnector on its own worker, which reports a {@link Recovered} to {@code sink}.
         *
         * @return false when the run has no reconnector, so the hold can only end in an abort
         */
        boolean startRecovery(String reason, float lastKnownForce, double envelopeNewton, RunEventSink sink);

        /** Re-arms both crossing edges, so the first crossing after a resume is not a repeat. */
        void resetEdges();

        /** The device delivered a fresh sample within the timeout and the stream is still flowing. */
        boolean freshDataFlowing(long freshTimeoutMs, long maxAgeMs);

        /** Leaves the measurement loop's hold and gate mode, so crossings are emitted again. */
        void endHold();

        /** Stops the measurement loop and the reconnector; runs more than once per run, so idempotent. */
        void teardown();

        /** Releases the drive connection this run made. */
        void destroy();
    }

    /** The motor-safety calls the loop makes. {@code MotorSafetyController} in production. */
    public interface Safety extends EmergencyStop {
        /**
         * Runs {@code action} as the energize: refused (throws) while a stop is latched or through a
         * plugin that does not gate its own calls; the link-loss stop armed before it, both in one
         * linked group fenced on the drive's stop epoch.
         */
        void energize(Consumer<Drive> action);

        /**
         * Runs {@code action} as one linked group unless a stop is latched, fenced on the drive's
         * stop epoch like the energize, so it never runs after a stop.
         *
         * @return false when latched, or a stop overtook the group, and the group did not finish
         */
        boolean withDriveUnlessLatched(Consumer<Drive> action);

        /**
         * Runs {@code action} against the drive, latched or not; refused (throws) through a plugin
         * that does not gate its own calls.
         */
        void withDrive(Consumer<Drive> action);

        /** Refused (throws) while a stop is still executing. */
        void clearStopLatch();

        /** How this run's last stop went; nulled by {@link #clearStopLatch()}. */
        @Nullable SafeStopResult lastStopResult();

        /** False after a stop past tier 1 left the drive without a handle. */
        boolean isDriveAvailable();
    }

    /** The incident's bookkeeping outside the loop: the gap sidecar and the state broadcast. */
    public interface Incidents {
        /** Gaps opened so far; the measurement loop opened the newest one before its loss event. */
        int gapCount();

        /** Closes gap {@code index} (counting from 1) with the incident's recovery and stop. */
        void endGap(int index, String outcome, @Nullable SensorReconnector.Outcome recovery,
                    @Nullable SafeStopResult stop);

        /** Pushes the current snapshot again; never throws. */
        void rebroadcast();
    }

    /**
     * The STARTING entry action up to the program: the run gate, the program, the bench and the
     * startup checks. Calls {@link #attach} as soon as the program exists; the loop then runs the
     * one start sequence of every type ({@link #begin}).
     */
    @FunctionalInterface
    public interface Startup {
        void start(RunLoop loop) throws Exception;
    }

    /** Whether a state acts on an event or drops it; there is no third outcome. */
    public enum Disposition {
        HANDLED, DROPPED
    }

    private static final Tick TICK = new Tick();

    private final TestStateMachine machine;
    private final Consumer<String> runLog;
    private final String typeName;
    private final Safety safety;
    private final RecoveryGates gates;
    private final Incidents incidents;
    private final Startup startup;
    private final Runnable onTerminal;
    private final ResumeGates resumeGates;
    /** Unbounded: an emitter must never block or lose an event, and a run produces few. */
    private final BlockingQueue<RunEvent> events = new LinkedBlockingQueue<>();

    /** Set on the loop thread by the startup; kept here so a stranded runner still tears it down. */
    private volatile TestProgram program;
    private volatile Devices devices;
    /** The limits in force, null before the start sequence reads them. Loop thread only. */
    private Limits limits;
    /** What the measurement loop compares against; moved with {@link #limits}. */
    private ForceLimits forceLimits;
    /** The direction last commanded, read by the program, not the drive (H-05). Loop thread only. */
    private Direction commandedDirection;

    // Incident facts the broadcast snapshot reads from other threads; written by the loop only.
    /** This incident's reconnector verdict, null until it arrives. */
    private volatile SensorReconnector.Outcome recovery;
    /** Epoch millis for the browser's countdown; the loop's own deadline is a nanoTime. */
    private volatile Long safeHoldDeadlineEpochMillis;

    // Loop thread only.
    private boolean ended = false;
    /** The gap sidecar entry of the open incident, counting from 1; 0 when none is open. */
    private int incidentGap = 0;
    /** The incident's stop, captured by a resume before {@code clearStopLatch()} discards it. */
    private SafeStopResult incidentStop;
    private boolean holdArmed = false;
    private long holdDeadlineNanos;
    private boolean rebroadcastArmed = false;
    private long nextRebroadcastNanos;
    /** {@link #INCIDENT_REBROADCAST_MS}; shortened by tests. */
    long rebroadcastMillis = INCIDENT_REBROADCAST_MS;

    /**
     * @param runLog     the run's {@code TestLogger}
     * @param typeName   the type as the run log has always named it, {@code null} when unset
     * @param gates      the recovery parameters this run was started with
     * @param onTerminal the runner's bookkeeping after the terminal entry action: outcome, scope
     */
    public RunLoop(TestStateMachine machine, Consumer<String> runLog, String typeName, Safety safety,
                   RecoveryGates gates, Incidents incidents, Startup startup, Runnable onTerminal) {
        this.machine = machine;
        this.runLog = runLog;
        this.typeName = typeName;
        this.safety = safety;
        this.gates = gates;
        this.incidents = incidents;
        this.startup = startup;
        this.onTerminal = onTerminal;
        this.resumeGates = new ResumeGates(gates, () -> {
            TestProgram current = this.program;
            return current == null ? ResumePolicy.ABORT_ONLY : current.resumePolicy();
        }, safety::isDriveAvailable, System::currentTimeMillis);
    }

    /**
     * The startup hands over the program it created and the run's devices, before anything that can
     * fail with them, so the teardown always reaches both.
     */
    public void attach(TestProgram created, Devices runDevices) {
        this.devices = runDevices;
        this.program = created;
    }

    /**
     * Any thread. A detector trip commits {@code FAULT} and a sensor loss {@code SENSOR_LOST} here,
     * on the thread that saw it and after it de-energized, so the audit record names it at once and
     * a resume in flight on the loop sees its run leave {@code RESUMING} (I11).
     */
    @Override
    public void accept(RunEvent event) {
        switch (event) {
            case CeilingTripped c -> machine.transition(TestState.FAULT, c.detail());
            case WatchdogFailed w -> machine.transition(TestState.FAULT, w.detail());
            // From RUNNING, or from RESUMING on a flapping link; refused in any other state.
            case SensorLost l -> machine.transition(TestState.SENSOR_LOST, l.reason());
            default -> {
                // Every other state change was committed by the emitter, or is the loop's to make.
            }
        }
        events.offer(event);
    }

    /** The server's single Resume verdict; the guard of {@code SAFE_HOLD -> RESUMING}. Any thread. */
    public boolean canResume() {
        return resumeGates.allowResume();
    }

    /** This incident's reconnector verdict, null before it arrives. Any thread. */
    public @Nullable SensorReconnector.Outcome recoveryOutcome() {
        return recovery;
    }

    /** When the standing hold is aborted unless resumed, epoch millis; null outside a hold. Any thread. */
    public @Nullable Long safeHoldDeadlineEpochMillis() {
        return safeHoldDeadlineEpochMillis;
    }

    /**
     * Drops queued crossings and timed turns: after an incident they describe a force nobody is
     * measuring any more. The state rule drops them too; this keeps one from outliving a resume.
     */
    void discardMotionEvents() {
        events.removeIf(event -> event instanceof LimitCrossed || event instanceof TimedTurn);
    }

    /**
     * The state x event table. Crossings and timed turns move the motor, so only a running run acts
     * on them; the incident events act only in the state their emitter committed; every ending event
     * ends the run in whatever state it arrives.
     */
    public static Disposition disposition(TestState state, RunEvent event) {
        boolean handled = switch (event) {
            case LimitCrossed c -> state == TestState.RUNNING;
            case TimedTurn t -> state == TestState.RUNNING;
            case OperatorStop s -> true;
            case Abort a -> true;
            case CeilingTripped c -> true;
            case WatchdogFailed w -> true;
            case PersistenceFailed p -> true;
            // SAFE_HOLD: lost again while the recovery gate was scoring the reconnected stream.
            case SensorLost l -> state == TestState.SENSOR_LOST || state == TestState.SAFE_HOLD;
            case Recovered r -> state == TestState.SAFE_HOLD;
            case ResumeAccepted r -> state == TestState.RESUMING;
            case Tick t -> state.isIncident();
        };
        return handled ? Disposition.HANDLED : Disposition.DROPPED;
    }

    @Override
    public void run() {
        try {
            startup.start(this);
            begin();
            settle(TestState.RUNNING, "setup complete, the motor is under force control");
            // RUNNING's entry re-arms nothing: a crossing seen during STARTING is queued and acted on
            // now, as the int queue dispatched it once the runner reached it.
            while (!ended) {
                // Holds no lock while it waits (I8): shutdown's safe stop never queues behind it.
                long timeout = pollTimeoutNanos();
                RunEvent event = timeout < 0 ? events.take() : events.poll(timeout, TimeUnit.NANOSECONDS);
                handle(event == null ? TICK : event);
            }
        } catch (InterruptedException e) {
            recordPendingFaults();
            runLog.accept("interrupt test " + typeName);
            logger.error("TestRunner Thread interrupted", e);
            settle(TestState.ABORTED, "the runner thread was interrupted");
        } catch (Exception e) {
            recordPendingFaults();
            // getClass() alone renders as "class java.lang.IllegalStateException" in the test log
            runLog.accept("error: " + e.getClass().getSimpleName() + ", " + e.getMessage());
            runLog.accept("error test " + typeName);
            logger.error("Exception occurred during test", e);
            // FAULT, not ABORTED: ABORTED means an operator or the safe-hold timer ended the run. A
            // Stop committed during STARTING already made it ABORTED, and this transition is refused.
            settle(TestState.FAULT, e.getClass().getSimpleName() + ": " + e.getMessage());
        } finally {
            enterTerminal();
        }
    }

    /**
     * The start sequence of every test type, after the startup attached the program: the limits,
     * the measurement loop, the program's start lines, the first sample at the device and in the
     * loop, the drive, then the program's {@code DriveSetup} as the one energize (I10, D3). The stop
     * latch was cleared when the run was started, before a Stop could latch it; clearing it here
     * would undo a Stop pressed during STARTING.
     */
    private void begin() {
        TestProgram current = this.program;
        Devices runDevices = this.devices;
        if (current == null || runDevices == null) {
            throw new IllegalStateException("the startup attached no program to run");
        }
        Limits initial = current.limits();
        this.limits = initial;
        this.forceLimits = new ForceLimits(initial.upper(), initial.lower(), initial.ceiling());
        runDevices.startMeasurement(forceLimits, this);
        current.onStart(this);
        runDevices.awaitFirstSample();
        runLog.accept("load cell delivering measurements");
        runDevices.connectDrive();
        energize(current.startup());
    }

    /** One energize, through the safety's latch check; the commanded direction once it took. */
    private void energize(DriveSetup setup) {
        safety.energize(setup::applyTo);
        commandedDirection = setup.direction();
    }

    /**
     * Disarms what belongs to a state the run has left - the hold expiry on any exit from SAFE_HOLD,
     * the re-broadcast once the incident is over - and waits for the nearest deadline left.
     *
     * @return nanos to wait, or -1 to wait for an event with no deadline
     */
    private long pollTimeoutNanos() {
        TestState state = machine.current();
        if (state != TestState.SAFE_HOLD && holdArmed) {
            // Cancelled on exit, so a resumed or aborted hold cannot be auto-aborted later.
            holdArmed = false;
            safeHoldDeadlineEpochMillis = null;
        }
        if (!state.isIncident()) {
            rebroadcastArmed = false;
        }

        long now = System.nanoTime();
        long timeout = -1;
        if (holdArmed) {
            timeout = Math.max(0, holdDeadlineNanos - now);
        }
        if (rebroadcastArmed) {
            long untilBroadcast = Math.max(0, nextRebroadcastNanos - now);
            timeout = timeout < 0 ? untilBroadcast : Math.min(timeout, untilBroadcast);
        }
        return timeout;
    }

    private void handle(RunEvent event) {
        TestState state = machine.current();
        if (disposition(state, event) == Disposition.DROPPED) {
            if (event instanceof Tick) {
                logger.debug("tick dropped in state {}", state);
            } else {
                logger.info("run event {} dropped in state {}", event, state);
            }
            return;
        }
        switch (event) {
            case LimitCrossed c -> act(program.onLimit(c.limit(), this));
            case TimedTurn t -> act(program.onTimedTurn(t.next(), this));
            case OperatorStop s -> finish(FinishCause.OPERATOR);
            case Abort a -> {
                runLog.accept("abort: " + a.reason());
                finish(FinishCause.ABORT);
            }
            case CeilingTripped c -> fault(c.detail());
            case WatchdogFailed w -> fault(w.detail());
            case PersistenceFailed p -> persistenceFailed(p.detail());
            case SensorLost l -> enterHold(l, state);
            case Recovered r -> recordRecovery(r.outcome());
            case ResumeAccepted r -> resume(r.actor());
            case Tick t -> tick(state);
        }
    }

    private void act(Verdict verdict) {
        if (verdict instanceof Verdict.Finish(FinishCause cause)) {
            finish(cause);
        }
    }

    private void fault(String detail) {
        recordFault(detail);
        finish(FinishCause.FAULT);
    }

    /**
     * The trace writer lost samples. Nothing was de-energized for it, so unlike a detector trip the
     * FAULT is committed here, on the loop, and the finish's own stop de-energizes (OQ-64 step 9).
     */
    private void persistenceFailed(String detail) {
        machine.transition(TestState.FAULT, detail);
        fault(detail);
    }

    /** The detector's trip, in the run log; {@link #accept} already committed the FAULT. */
    private void recordFault(String detail) {
        runLog.accept(detail);
        runLog.accept("abort: " + detail);
    }

    /**
     * A run that throws in its STARTING entry never reaches the queue, but a trip that made it throw
     * (its stop latched the energize out), or an Abort that latched it, is still owed its run-log
     * lines, ahead of the error.
     */
    private void recordPendingFaults() {
        if (program == null) {
            return;
        }
        for (Iterator<RunEvent> it = events.iterator(); it.hasNext(); ) {
            RunEvent event = it.next();
            switch (event) {
                case CeilingTripped c -> {
                    it.remove();
                    recordFault(c.detail());
                }
                case WatchdogFailed w -> {
                    it.remove();
                    recordFault(w.detail());
                }
                case PersistenceFailed p -> {
                    it.remove();
                    machine.transition(TestState.FAULT, p.detail());
                    recordFault(p.detail());
                }
                case Abort a -> {
                    it.remove();
                    runLog.accept("abort: " + a.reason());
                }
                default -> {
                }
            }
        }
    }

    // ---- the incident path -------------------------------------------------------------------

    /**
     * The SENSOR_LOST entry action, then SAFE_HOLD's: the measurement thread already stopped the
     * motor, opened the gap and committed SENSOR_LOST. Order as the synchronous loss path had it:
     * hold entry, SAFE_HOLD, the reconnector.
     */
    private void enterHold(SensorLost loss, TestState state) {
        try {
            // A loss while the last incident's resume was in flight supersedes that incident's gap.
            int gap = incidents.gapCount();
            if (incidentGap > 0 && incidentGap != gap) {
                closeGap("SUPERSEDED");
            }
            incidentGap = gap;
            incidentStop = null;
            recovery = null;
            resumeGates.lossBegan();

            if (state == TestState.SAFE_HOLD) {
                // Lost again during the recovery gate: the loss failed the gate, and the reconnector
                // already running reports that verdict. The hold and its deadline stand.
                return;
            }

            rebroadcastArmed = true;
            nextRebroadcastNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(rebroadcastMillis);

            program.onHoldEntry();

            // Armed before the commit, so the SAFE_HOLD broadcast already carries the deadline.
            long timeout = gates.safeHoldTimeoutMillis();
            resumeGates.holdEntered();
            holdDeadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeout);
            safeHoldDeadlineEpochMillis = System.currentTimeMillis() + timeout;
            holdArmed = true;
            if (!machine.transition(TestState.SAFE_HOLD, loss.reason())) {
                // A Stop or Abort got there first; its own event ends the run.
                return;
            }

            // Floored here, once; the gate treats the envelope as final.
            double envelope = Math.max(limits == null ? 0 : limits.envelope(), gates.minEnvelopeNewton());
            if (!devices.startRecovery(loss.reason(), loss.lastKnownForce(), envelope, this)) {
                runLog.accept("no reconnector wired for this run, the hold can only end in an abort");
            }
        } catch (Throwable t) {
            logger.error("sensor-loss handling failed, the run cannot be held safely", t);
            runLog.accept("error while entering the safe hold: " + t);
            if (machine.transition(TestState.FAULT, "sensor-loss handling failed: " + t)) {
                finish(FinishCause.FAULT);
            }
        }
    }

    /** Stored before it is logged: the run-log line is what an operator resumes on. */
    private void recordRecovery(SensorReconnector.Outcome outcome) {
        recovery = outcome;
        resumeGates.verdict(outcome.recovered() && outcome.gate() != null && outcome.gate().passed());
        runLog.accept("sensor recovery after " + outcome.attempts() + " attempt(s): " + outcome.detail());
    }

    /**
     * The RESUMING entry action, {@code TestRunnerThread#performResume} moved in order; the order
     * is owned by {@code safety-and-recovery.md}. A Stop, Abort or loss moves the run out of
     * RESUMING before it touches the latch (I11), so each step re-checks the state.
     */
    private void resume(String actor) {
        try {
            // First: the edge detector still holds the pre-loss crossing, which would filter the
            // first post-resume crossing as a repeat.
            devices.resetEdges();
            // The run is not RUNNING, so the state rule would drop these anyway; this keeps one
            // queued before the resume from outliving it.
            discardMotionEvents();

            // Re-checked: the link may have dropped since the gate passed, and the watchdog must be
            // armed on live data before the drive is re-enabled.
            if (!devices.freshDataFlowing(RESUME_FRESH_DATA_TIMEOUT_MS, RESUME_DATA_FLOW_MAX_AGE_MS)) {
                throw new IllegalStateException("the load cell is not delivering measurements,"
                        + " refusing to re-energize the motor without force feedback");
            }

            // Must be read before clearStopLatch(), which nulls it; the gap record names it.
            incidentStop = safety.lastStopResult();

            // Atomic with the state check: a Stop or Abort moves the run out of RESUMING before it
            // latches, so a stop that already finished cannot have its latch cleared from here.
            if (!machine.runWhileIn(TestState.RESUMING, safety::clearStopLatch)) {
                logger.info("resume superseded in state {}, the drive stays de-energized", machine.current());
                return;
            }
            // A stop arriving from here on either latches before this energize (which refuses) or
            // stops the drive after it.
            energize(program.resumePolicy().resumeSetup(this));

            if (!machine.transition(TestState.RUNNING, "sensor recovered, drive re-initialised", actor)) {
                // Superseded between energize and here: undo the energize instead of trusting the
                // stop that superseded it to come after.
                reportStop(safety.stop("resume superseded in state " + machine.current()));
                return;
            }
            // RUNNING is what lets the loop act on crossings again; endHold() lets the measurement
            // loop emit them.
            devices.endHold();

            closeGap("RESUMED");
        } catch (Throwable t) {
            logger.error("resume failed, stopping the motor", t);
            boolean faulted = machine.transition(TestState.FAULT, "resume failed: " + t, actor);
            SafeStopResult result = safety.stop("resume failed: " + t);
            if (result.needsOperatorAttention()) {
                logger.error("Resume failed AND the motor stop could not be verified: {}", result.detail());
            }
            closeGap("RESUME_FAILED");
            if (faulted) {
                // The loop's own terminal transition: nobody else will send the event that ends it.
                finish(FinishCause.FAULT);
            }
        }
    }

    /** A deadline passed during an incident: re-broadcast, and abort a hold left unattended. */
    private void tick(TestState state) {
        long now = System.nanoTime();
        if (rebroadcastArmed && now - nextRebroadcastNanos >= 0) {
            nextRebroadcastNanos = now + TimeUnit.MILLISECONDS.toNanos(rebroadcastMillis);
            incidents.rebroadcast();
        }
        if (state == TestState.SAFE_HOLD && holdArmed && now - holdDeadlineNanos >= 0) {
            holdExpired();
        }
    }

    /**
     * Server-side auto-abort of an unattended hold. Bookkeeping, not safety: the loss path already
     * stopped the motor, and {@code maxHoldForResumeMillis} expires before this fires. Under the
     * state lock against a Resume, as the old timer's abort was; motor first, then the abort.
     */
    private void holdExpired() {
        holdArmed = false;
        safeHoldDeadlineEpochMillis = null;
        String reason = "aborted by " + SAFE_HOLD_TIMEOUT_ACTOR;
        if (!machine.compareAndTransition(TestState.SAFE_HOLD, TestState.ABORTED, reason,
                SAFE_HOLD_TIMEOUT_ACTOR, () -> true)) {
            return;
        }
        SafeStopResult result = safety.stop(reason);
        if (result.needsOperatorAttention()) {
            logger.error("Abort requested but the motor stop could not be verified: {}", result.detail());
        }
        runLog.accept("abort: " + reason);
        finish(FinishCause.ABORT);
    }

    /**
     * Closes the incident's gap, naming the incident's own stop: the one a resume captured before
     * {@code clearStopLatch()} discarded it, else the controller's last, which no resume cleared.
     */
    private void closeGap(String outcome) {
        if (incidentGap <= 0) {
            return;
        }
        int gap = incidentGap;
        SafeStopResult stop = incidentStop != null ? incidentStop : safety.lastStopResult();
        incidentGap = 0;
        incidentStop = null;
        try {
            incidents.endGap(gap, outcome, recovery, stop);
        } catch (Throwable t) {
            // The sidecar is evidence, not control flow.
            logger.error("could not close gap {} as {}", gap, outcome, t);
        }
    }

    private void reportStop(SafeStopResult result) {
        if (result.needsOperatorAttention()) {
            logger.error("Stop could not be verified: {}", result.detail());
        }
    }

    // ---- RunControl: what a program may do, on the loop thread ----------------------------------

    /**
     * Refused while a stop is latched, and fenced on the drive's stop epoch read before that check:
     * the latch is set before any stop reaches the drive, so no reversal can follow a stop - a
     * crossing the loop was already acting on when a loss stopped the motor included.
     */
    @Override
    public boolean turn(Direction direction) {
        boolean commanded = safety.withDriveUnlessLatched(drive -> drive.setDirection(direction.isRelease()));
        if (commanded) {
            // Only after the drive accepted it: a failed write leaves the old direction, so the next
            // crossing retries.
            commandedDirection = direction;
        } else {
            logger.info("reversal to {} refused, a motor stop is latched",
                    direction == Direction.RELEASE ? "release" : "pull");
        }
        return commanded;
    }

    @Override
    public @Nullable Direction direction() {
        return commandedDirection;
    }

    /**
     * Fenced like {@link #turn}: a setpoint queued before a stop never reaches the drive after it.
     * A zero rpm would itself be a stop and end the group; no program commands one (BL-156).
     */
    @Override
    public void runPhaseSpeed(int rpm, @Nullable Ramp ramp) {
        boolean commanded = safety.withDriveUnlessLatched(drive -> {
            if (ramp != null) {
                drive.setSecondSpeedRampTime(ramp.accelerationTenths(), ramp.decelerationTenths());
            } else {
                drive.setUseSecondRamp(false);
            }
            drive.setSpeedReferenceValueAsRpm(rpm);
        });
        if (!commanded) {
            logger.info("phase speed {} rpm not commanded, a motor stop is latched", rpm);
        }
    }

    @Override
    public void disableSecondRamp() {
        safety.withDrive(drive -> drive.setUseSecondRamp(false));
    }

    @Override
    public RunControl.Envelope envelope() {
        return devices.envelope();
    }

    @Override
    public void resetEnvelopeMin(float value) {
        devices.resetEnvelopeMin(value);
    }

    @Override
    public void resetEnvelopeMax(float value) {
        devices.resetEnvelopeMax(value);
    }

    @Override
    public float lastForce() {
        Devices current = this.devices;
        return current == null ? Float.NaN : current.lastForce();
    }

    @Override
    public Limits limits() {
        return limits;
    }

    /** Validated before the measurement loop sees it (I13). */
    @Override
    public void adaptLimits(double upper, double lower) {
        Limits adapted = limits.adapted(upper, lower);
        limits = adapted;
        forceLimits.adapt(adapted.upper(), adapted.lower());
    }

    @Override
    public RunEventSink timedTurns() {
        return devices.timedTurns();
    }

    @Override
    public void log(String line) {
        runLog.accept(line);
    }

    // ---- the end -----------------------------------------------------------------------------

    /**
     * The end of a run that did not throw: stop, teardown, the finish hook, then FINISHED unless the
     * run already settled elsewhere. The terminal entry action re-asserts the stop afterwards.
     */
    private void finish(FinishCause requested) {
        MotorStop stop = motorStop(stopAndTeardown());
        // A run an Abort or a trip already ended is not a finish, whatever the verdict said.
        FinishCause cause = switch (machine.current()) {
            case ABORTED -> FinishCause.ABORT;
            case FAULT -> FinishCause.FAULT;
            default -> requested;
        };
        program.onFinished(cause, stop, this);
        runLog.accept(program.name() + " finishing test");
        settle(TestState.FINISHED, "the test reached its end condition");
        ended = true;
    }

    /**
     * Exactly once per run, on every exit: the stop re-asserted (idempotent), the teardown, a gap
     * still open closed with the state the run ended in, then the runner's bookkeeping.
     */
    private void enterTerminal() {
        Devices current = this.devices;
        if (current != null && this.program != null) {
            try {
                stopAndTeardown();
                current.destroy();
            } catch (Exception e) {
                runLog.accept("error: " + e.getClass().getSimpleName() + ", " + e.getMessage());
                logger.error("Exception during cleanup/destroy", e);
                retryShutdown(current);
            }
        }
        // Every exit reaches a terminal state: a run left claiming STARTING or RUNNING is swept as
        // an orphan on the next boot.
        settle(TestState.FAULT, "the run ended without reaching a terminal state");
        holdArmed = false;
        rebroadcastArmed = false;
        safeHoldDeadlineEpochMillis = null;
        // A hold the run never left: ABORTED by a Stop or Abort, FAULT by a trip.
        closeGap(machine.current().name());
        onTerminal.run();
    }

    /**
     * What a program may know of a stop: the runner's {@link SafeStopResult} stays in the loop, so the
     * program package never depends on it ({@code ArchitectureTest}).
     */
    static MotorStop motorStop(SafeStopResult result) {
        if (result.verified()) {
            return MotorStop.CONFIRMED;
        }
        if (result.coasting()) {
            return MotorStop.COASTING;
        }
        return result.needsOperatorAttention() ? MotorStop.UNCONFIRMED : MotorStop.NEVER_ENERGIZED;
    }

    /**
     * Idempotent: the motor stop never throws, and the teardown may run twice.
     *
     * @return this stop's result, for the finish hook
     */
    private SafeStopResult stopAndTeardown() {
        SafeStopResult result = safety.stop("test cleanup");
        if (result.coasting()) {
            runLog.accept("motor de-energized, coasting down at " + result.motorSpeedRpm() + " rpm");
        } else if (result.needsOperatorAttention()) {
            // Only for a motor this run energized, so a failed startup check raises no E-stop alert.
            // Never a verified claim: past tier 1 the drive may run on until its own link-loss stop.
            runLog.accept("WARNING: motor stop could not be confirmed (" + result.detail()
                    + ") - use the physical E-stop");
        }
        devices.teardown();
        program.teardown(this);
        return result;
    }

    private void retryShutdown(Devices current) {
        try {
            current.destroy();
        } catch (Exception e) {
            logger.error("Retrying destroy() failed, forcing a safe stop", e);
        }

        SafeStopResult result = safety.stop("cleanup failed");
        if (result.coasting()) {
            logger.warn("Emergency stop de-energized the drive, motor still coasting at {} rpm", result.motorSpeedRpm());
        } else if (result.needsOperatorAttention()) {
            logger.error("Emergency stop could not be verified: {}", result.detail());
        }
    }

    /** A transition the loop makes only while the run has not ended elsewhere. */
    private void settle(TestState state, String reason) {
        if (!machine.current().isTerminal()) {
            machine.transition(state, reason);
        }
    }
}
