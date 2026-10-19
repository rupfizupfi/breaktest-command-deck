package ch.rupfizupfi.deck.device.simulated;

import ch.rupfizupfi.deck.device.api.ControlSource;
import ch.rupfizupfi.deck.device.api.Measurement;
import ch.rupfizupfi.deck.device.api.StreamFailure;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/**
 * One plant model that the simulated drive writes to and the simulated load cell reads from. They
 * cannot be independent — a cyclic run closes its loop through the hardware.
 * <p>
 * Model, update rules and the watchdog constants every emitted value must respect:
 * {@code doc/dev/backend/simulated-bench.md}. The specimen and the sensor noise are
 * measured ({@link SpecimenProfile}); the drive's ramp and coast are not.
 */
@Component
@ConditionalOnProperty(name = "deck.hardware.mode", havingValue = "simulated")
public class SimulatedBench {

    private static final Logger logger = LoggerFactory.getLogger(SimulatedBench.class);

    /**
     * Offset a freshly registered stream reports while {@link SimulatedFault#LOAD_CELL_RECONNECT_GARBAGE}
     * is armed. Above the drift gate ({@code RecoveryProperties} driftFraction of the floored
     * envelope) but far below {@code MeasurementLoop}'s plausibility bound, so the drift branch rejects it.
     */
    private static final double RECONNECT_GARBAGE_OFFSET_NEWTON = 25_000;

    /**
     * How long the garbage offset lasts; longer than {@code plausibilityGateMillis}, so every sample
     * the gate inspects carries it.
     */
    private static final long RECONNECT_GARBAGE_WINDOW_NANOS = TimeUnit.MILLISECONDS.toNanos(500);

    /**
     * How long a stream survives while {@link SimulatedFault#LOAD_CELL_FLAPPING} is armed. Longer than
     * the first reconnect backoff plus the plausibility gate, so each cycle completes a resume before
     * the next loss and exercises the loss counter.
     */
    private static final long FLAPPING_STREAM_LIFETIME_NANOS = TimeUnit.SECONDS.toNanos(2);

    private final SimulatedBenchProperties properties;
    private final SimulatedFaultSwitches faults;

    /** What the next mount uses; set per run by {@link #prepareRun}. */
    private volatile SpecimenProfile profile;
    private volatile double pretensionNewton;

    /**
     * Real breaks reach 10 % of the peak inside one ~9 ms sample; this time constant gets there in
     * 10 ms, so the collapse spans a couple of ticks rather than a single step.
     */
    private static final double BREAK_DECAY_NANOS = TimeUnit.MILLISECONDS.toNanos(10) / Math.log(10);

    /**
     * Where the cell settles once the broken ends have sprung back: the median of the real runs
     * that recorded the settle. Below every lower shut-off threshold the bench has used, so a
     * destructive run ends on it.
     */
    private static final double RESIDUAL_AFTER_BREAK_NEWTON = 85;

    /** Held for the frozen-value fault, which must repeat a bit-identical float. */
    private volatile float lastEmittedForce = 0f;

    /** Written by drive handles, read by the tick thread. */
    private volatile int setpointRpm = 0;
    private volatile boolean generalEnabled = false;
    private volatile boolean started = false;
    /** True is release (force falls), false is pull (force rises) — matches {@code driveRelease}/{@code drivePull}. */
    private volatile boolean directionForward = true;
    /** The control word's second-ramp bit; the plant ramps at one rate either way. */
    private volatile boolean useSecondRamp = false;

    private volatile double measuredRpm = 0;
    private volatile Specimen specimen;
    private volatile double positionMm;
    private volatile boolean fractured = false;
    private volatile long fracturedAtNanos;
    private volatile double fractureForceNewton;
    /** Tracked only to spot the rising edge that means "new run, new specimen". */
    private boolean wasEnergized = false;

    /**
     * The drive's link watchdog, modelled on the CFW11's P0313/P0314: drive-side state shared by
     * every handle, kept across runs and never disarmed. Zero is off, the drive's own default.
     */
    private volatile long linkLossTimeoutNanos = 0;
    private volatile int communicationErrorAction = 0;

    /** The drive's LOC/REM state. No keypad exists here, so only a test moves it. */
    private volatile ControlSource controlSource = ControlSource.REMOTE;
    /** When a request last reached the drive from any handle. */
    private volatile long lastRequestNanos = System.nanoTime();
    /** Bus time of one drive transaction on any handle; seeded from the properties, set by tests. */
    private volatile Duration transactionLatency;
    private final Object wedgeMonitor = new Object();
    /** Bumped by {@link #endWedge()}; guarded by {@link #wedgeMonitor}. */
    private long wedgeReleases;
    /** Set when the watchdog fires, cleared by the next request; keeps the trip to one log line. */
    private volatile boolean linkLossTripped = false;

    private final Set<SimulatedLoadCellStream> streams = new CopyOnWriteArraySet<>();

    /** nanoTime of the most recent {@link #register}; the age both reconnect faults are keyed on. */
    private volatile long lastStreamRegisteredNanos = System.nanoTime();

    private volatile Thread tickThread;
    private volatile boolean running = false;

    public SimulatedBench(SimulatedBenchProperties properties, SimulatedFaultSwitches faults) {
        this.properties = properties;
        this.faults = faults;

        this.transactionLatency = Duration.ofMillis(properties.getTransactionLatencyMillis());
        this.profile = SpecimenProfile.resolve(properties.getProfile());
        this.pretensionNewton = profile.pretensionNewton();
        mount();
    }

    @PostConstruct
    void start() {
        running = true;
        tickThread = new Thread(this::tickLoop, "simulated-bench");
        tickThread.setDaemon(true);
        tickThread.start();
        logger.warn("SIMULATED HARDWARE: plant model running (profile '{}' until a run selects one, model {})."
                        + " Force traces are typical of the profile, NOT a real result.",
                profile.key(), properties.getSampleModel());
    }

    @PreDestroy
    void stop() {
        running = false;
        Thread thread = tickThread;
        if (thread != null) {
            thread.interrupt();
        }
    }

    SimulatedFaultSwitches faults() {
        return faults;
    }

    /**
     * Discards the specimen for a fresh one at its pretension, with its own break force and length.
     * Called from two triggers; see the plant-model section of
     * {@code doc/dev/backend/simulated-bench.md}. Both miss a load cell held open across
     * two runs while a failed stop left the drive energized, so the second run inherits the first
     * one's specimen (simulated mode only).
     */
    void mountNewSpecimen(String reason) {
        // Remounting mid-run would fail every simulated resume's drift gate. The primary guard is
        // SimulatedLoadCellStreamProvider#open(boolean), which skips a reconnect's remount.
        if (generalEnabled || measuredRpm != 0) {
            logger.info("Simulated bench: keeping the current specimen, the machine is running ({})", reason);
            return;
        }

        if (positionMm == specimen.mountPositionMm() && !fractured) {
            return;
        }
        mount();
        logger.info("Simulated bench: new specimen mounted, breaks at {} N ({})",
                Math.round(specimen.breakForceNewton()), reason);
    }

    /**
     * Mounts a fresh specimen of {@code profile} at {@code pretensionNewton} for the run about to
     * start; the triggers in {@link #mountNewSpecimen} then find it fresh and keep it. While the
     * machine is still running the current specimen stays, and the choice applies to the next mount.
     */
    void prepareRun(SpecimenProfile profile, double pretensionNewton, String reason) {
        this.profile = profile;
        this.pretensionNewton = pretensionNewton;
        if (generalEnabled || measuredRpm != 0) {
            logger.info("Simulated bench: profile '{}' applies from the next mount, the machine is running ({})",
                    profile.key(), reason);
            return;
        }
        mount();
        logger.info("Simulated bench: mounted '{}' at {} N pretension, breaks at {} N ({})", profile.key(),
                Math.round(pretensionNewton), Math.round(specimen.breakForceNewton()), reason);
    }

    /** The specimen currently mounted. */
    Specimen specimen() {
        return specimen;
    }

    private void mount() {
        specimen = properties.isScatter()
                ? Specimen.draw(profile, pretensionNewton, ThreadLocalRandom.current())
                : Specimen.nominal(profile, pretensionNewton);
        positionMm = specimen.mountPositionMm();
        fractured = false;
        lastEmittedForce = 0f;
        // LoadCellDevice drains every 20 ms, so a run's first batch would otherwise carry readings
        // of the previous specimen; one left above the new run's upper threshold ends it at once.
        for (SimulatedLoadCellStream stream : streams) {
            stream.discardPending();
        }
    }

    void register(SimulatedLoadCellStream stream) {
        // Stream age is how the reconnect faults tell a reconnect apart; the bench is not told.
        lastStreamRegisteredNanos = System.nanoTime();
        streams.add(stream);
    }

    void unregister(SimulatedLoadCellStream stream) {
        streams.remove(stream);
    }

    // ---- drive side -------------------------------------------------------

    void setSpeedReferenceRpm(int rpm) {
        setpointRpm = rpm;
    }

    int speedReferenceRpm() {
        return setpointRpm;
    }

    void setGeneralEnabled(boolean enabled) {
        generalEnabled = enabled;
    }

    boolean generalEnabled() {
        return generalEnabled;
    }

    void setStarted(boolean started) {
        this.started = started;
    }

    boolean started() {
        return started;
    }

    void setDirectionForward(boolean forward) {
        directionForward = forward;
    }

    boolean directionForward() {
        return directionForward;
    }

    void setUseSecondRamp(boolean useSecondRamp) {
        this.useSecondRamp = useSecondRamp;
    }

    boolean useSecondRamp() {
        return useSecondRamp;
    }

    /** The measured shaft speed; echoing the setpoint would bypass the stop verification. */
    int measuredRpm() {
        return (int) Math.round(measuredRpm);
    }

    /**
     * Puts the shaft at this speed, as a coasting mass or a hand on the coupling would. For tests of
     * a bench whose tick is not running; a running tick ramps it on from here.
     */
    void setMeasuredRpm(int rpm) {
        measuredRpm = rpm;
    }

    /** Public so a deck test can put the simulated drive under keypad control. */
    public void setControlSource(ControlSource controlSource) {
        this.controlSource = controlSource;
    }

    ControlSource controlSource() {
        return controlSource;
    }

    // ---- drive bus --------------------------------------------------------

    /**
     * How long every drive transaction takes before it reaches the plant: what makes "a stop waits
     * for the transaction in flight" observable at all.
     */
    Duration transactionLatency() {
        return transactionLatency;
    }

    /** Package-private so a contract fixture can slow the bus; zero makes it instant. */
    void setTransactionLatency(Duration latency) {
        transactionLatency = latency;
    }

    /** How long {@link SimulatedFault#DRIVE_TRANSACTION_WEDGED} holds the transaction it takes. */
    Duration wedge() {
        return Duration.ofMillis(properties.getWedgeMillis());
    }

    /**
     * Holds the calling transaction for {@link #wedge()}, or until {@link #endWedge()}. Immune to
     * interrupt, like the native transfer it stands for; the interrupt is kept.
     */
    void holdWedge() {
        long end = System.nanoTime() + wedge().toNanos();
        boolean interrupted = false;
        synchronized (wedgeMonitor) {
            long generation = wedgeReleases;
            for (long left; wedgeReleases == generation && (left = end - System.nanoTime()) > 0; ) {
                try {
                    TimeUnit.NANOSECONDS.timedWait(wedgeMonitor, left);
                } catch (InterruptedException e) {
                    interrupted = true;
                }
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Ends every wedge in progress now, as a transfer that finally completes: the wedged
     * transaction then reaches the drive and the calls queued behind it follow. A test's way out of
     * a wedge set to never end.
     */
    public void endWedge() {
        synchronized (wedgeMonitor) {
            wedgeReleases++;
            wedgeMonitor.notifyAll();
        }
    }

    // ---- drive link watchdog ----------------------------------------------

    /** Every request a live handle answers counts, as any valid telegram does on the real drive. */
    void requestReceived() {
        lastRequestNanos = System.nanoTime();
        linkLossTripped = false;
    }

    long lastRequestNanos() {
        return lastRequestNanos;
    }

    void setCommunicationErrorAction(int action) {
        communicationErrorAction = action;
    }

    int communicationErrorAction() {
        return communicationErrorAction;
    }

    void setLinkLossTimeoutNanos(long timeoutNanos) {
        linkLossTimeoutNanos = timeoutNanos;
    }

    long linkLossTimeoutNanos() {
        return linkLossTimeoutNanos;
    }

    boolean linkLossTripped() {
        return linkLossTripped;
    }

    /**
     * Applies the communication-error action once no request has arrived within the timeout. Codes
     * as {@code Drive#setActionInCaseOfCommunicationError}: 1 stops via start/stop, 2 removes general
     * enable, 5 is a fault trip, which also removes it; the rest change nothing the plant models.
     * Package-private so a test can step it with its own clock.
     */
    void checkLinkWatchdog(long nowNanos) {
        long timeout = linkLossTimeoutNanos;
        if (timeout <= 0 || linkLossTripped || nowNanos - lastRequestNanos <= timeout) {
            return;
        }
        linkLossTripped = true;
        if (!generalEnabled && !started) {
            // Between runs: the real drive raises its alarm, but there is nothing to stop.
            return;
        }
        int action = communicationErrorAction;
        switch (action) {
            case 1 -> started = false;
            case 2, 5 -> generalEnabled = false;
            default -> {
                logger.warn("SIMULATED DRIVE: no request for {} ms, but communication-error action {} "
                        + "does not stop the motor", TimeUnit.NANOSECONDS.toMillis(timeout), action);
                return;
            }
        }
        logger.warn("SIMULATED DRIVE: no request for {} ms, link-loss stop applied action {}",
                TimeUnit.NANOSECONDS.toMillis(timeout), action);
    }

    // ---- plant ------------------------------------------------------------

    private void tickLoop() {
        long previous = System.nanoTime();
        while (running) {
            try {
                TimeUnit.MILLISECONDS.sleep(properties.getTickMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }

            long now = System.nanoTime();
            // Measured, so a descheduled tick advances the plant by the real elapsed time.
            double dtSeconds = (now - previous) / 1_000_000_000.0;
            previous = now;

            try {
                advance(dtSeconds);
            } catch (RuntimeException e) {
                logger.warn("Simulated bench tick failed, continuing", e);
            }
        }
    }

    private void advance(double dtSeconds) {
        checkLinkWatchdog(System.nanoTime());
        boolean energized = generalEnabled && started;
        // One of two independent triggers for a new specimen; see mountNewSpecimen().
        if (energized && !wasEnergized) {
            mountNewSpecimen("drive energized");
        }
        wasEnergized = energized;

        double target = energized ? setpointRpm : 0;
        double rate = energized ? properties.getRampRpmPerSecond() : properties.getCoastRpmPerSecond();
        double step = rate * dtSeconds;

        double rpm = measuredRpm;
        // The drive still answers; only the shaft ignores it.
        boolean shaftFrozen = faults.isActive(SimulatedFault.DRIVE_MOTOR_NEVER_SLOWS);
        if (!shaftFrozen) {
            if (rpm < target) {
                rpm = Math.min(target, rpm + step);
            } else if (rpm > target) {
                rpm = Math.max(target, rpm - step);
            }
            measuredRpm = rpm;
        }

        // Pull (directionForward == false) raises force; release can unload the specimen to zero.
        double travel = rpm * properties.getMmPerRev() * dtSeconds / 60.0;
        double position = positionMm + (directionForward ? -travel : travel);
        positionMm = Math.max(0, position);

        emit(forceFor(positionMm));
    }

    /**
     * Turns the plant's force into the sample the streams see, applying whichever load-cell fault is
     * armed. Injected here so every subscriber sees the same broken sensor.
     */
    private void emit(double plantForce) {
        if (faults.isActive(SimulatedFault.LOAD_CELL_STREAM_DEATH)) {
            // -800 is CommandExecutionException.NON_NUMERIC_VALUE, a code in the real driver's table.
            var cause = new StreamFailure("-800", "CommandExecutionException",
                    "simulated driver fault: non-numeric value returned while the driver reported success");
            for (SimulatedLoadCellStream stream : streams) {
                stream.fail(cause);
            }
            return;
        }

        if (faults.isActive(SimulatedFault.LOAD_CELL_FLAPPING)
                && System.nanoTime() - lastStreamRegisteredNanos >= FLAPPING_STREAM_LIFETIME_NANOS) {
            // Fires once per stream: fail() unregisters it until a reconnect registers a replacement.
            var cause = new StreamFailure("-800", "CommandExecutionException",
                    "simulated driver fault: the stream died again shortly after it was reconnected");
            for (SimulatedLoadCellStream stream : streams) {
                stream.fail(cause);
            }
            return;
        }

        if (faults.isActive(SimulatedFault.LOAD_CELL_SILENT)) {
            // Still "reading" as far as the driver is concerned: the sensor just went quiet.
            return;
        }

        if (faults.isActive(SimulatedFault.LOAD_CELL_DROPPED_SAMPLES)) {
            for (SimulatedLoadCellStream stream : streams) {
                stream.drop();
            }
            return;
        }

        float force;
        if (faults.isActive(SimulatedFault.LOAD_CELL_FROZEN)) {
            // The previous sample verbatim: the detector compares raw float bits.
            force = lastEmittedForce;
        } else if (faults.isActive(SimulatedFault.LOAD_CELL_NAN)) {
            force = Float.NaN;
        } else if (faults.isActive(SimulatedFault.LOAD_CELL_IMPLAUSIBLE_FORCE)) {
            force = 1_000_000f;
        } else {
            force = (float) (plantForce + noise() + reconnectGarbageOffset());
            lastEmittedForce = force;
        }

        long timestamp = System.currentTimeMillis();
        for (SimulatedLoadCellStream stream : streams) {
            stream.offer(new Measurement(force, timestamp));
        }
    }

    /**
     * A cell that came back mis-zeroed: the plant is untouched, only the reading is offset, and only
     * for the first samples of a freshly registered stream. Zero unless the fault is armed.
     */
    private double reconnectGarbageOffset() {
        if (!faults.isActive(SimulatedFault.LOAD_CELL_RECONNECT_GARBAGE)) {
            return 0;
        }

        long age = System.nanoTime() - lastStreamRegisteredNanos;
        return age < RECONNECT_GARBAGE_WINDOW_NANOS ? RECONNECT_GARBAGE_OFFSET_NEWTON : 0;
    }

    private double forceFor(double position) {
        if (fractured) {
            double age = System.nanoTime() - fracturedAtNanos;
            return RESIDUAL_AFTER_BREAK_NEWTON
                    + (fractureForceNewton - RESIDUAL_AFTER_BREAK_NEWTON) * Math.exp(-age / BREAK_DECAY_NANOS);
        }

        Specimen current = specimen;
        double force = current.forceAt(position);

        if (properties.getSampleModel() == SimulatedBenchProperties.SampleModel.FRACTURE
                && force >= current.breakForceNewton()) {
            fractured = true;
            fracturedAtNanos = System.nanoTime();
            fractureForceNewton = force;
            logger.warn("SIMULATED HARDWARE: specimen fractured at {} N", Math.round(force));
            return force;
        }

        return Math.min(force, properties.getMaxForceNewton());
    }

    /** Applied AFTER the zero clamp, or an unloaded specimen trips the frozen-sample detector. */
    private double noise() {
        double sigma = properties.getNoiseNewton();
        return sigma <= 0 ? 0 : ThreadLocalRandom.current().nextGaussian() * sigma;
    }
}
