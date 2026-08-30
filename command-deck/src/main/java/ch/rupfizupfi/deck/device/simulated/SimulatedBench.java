package ch.rupfizupfi.deck.device.simulated;

import ch.rupfizupfi.deck.device.api.Measurement;
import ch.rupfizupfi.deck.device.api.StreamFailure;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/**
 * One plant model that the simulated drive writes to and the simulated load cell reads from. They
 * cannot be independent — a cyclic run closes its loop through the hardware.
 * <p>
 * Why one model, the update rule of each state variable, and the watchdog constants every value
 * emitted here has to respect: {@code doc/06-feature-work/virtual-devices/README.md}. All
 * parameters are invented ({@link SimulatedBenchProperties}) and the traces are shape-plausible,
 * not calibrated.
 */
@Component
@ConditionalOnProperty(name = "deck.hardware.mode", havingValue = "simulated")
public class SimulatedBench {

    private static final Logger logger = LoggerFactory.getLogger(SimulatedBench.class);

    /**
     * Offset a freshly registered stream reports while {@link SimulatedFault#LOAD_CELL_RECONNECT_GARBAGE}
     * is armed. Deliberately between the two gates: above the drift gate ({@code RecoveryProperties}
     * driftFraction of the envelope, floored at minEnvelopeNewton) so the reconnect is rejected as
     * drifted, and far below {@code LoadCellThread}'s plausibility bound so it is not rejected as
     * impossible first — that would exercise the wrong branch and leave drift as untested as before.
     */
    private static final double RECONNECT_GARBAGE_OFFSET_NEWTON = 25_000;

    /**
     * How long the garbage offset lasts. Longer than {@code plausibilityGateMillis}, so every sample
     * the gate inspects carries it — a shorter window would let the reconnect pass on the samples
     * that arrived after the offset cleared.
     */
    private static final long RECONNECT_GARBAGE_WINDOW_NANOS = TimeUnit.MILLISECONDS.toNanos(500);

    /**
     * How long a stream survives while {@link SimulatedFault#LOAD_CELL_FLAPPING} is armed. Longer than
     * the first reconnect backoff plus the plausibility gate, so each cycle is a resume that genuinely
     * completed followed by a fresh loss — a shorter life would only ever exercise reconnect, never
     * the loss counter the cap is built on.
     */
    private static final long FLAPPING_STREAM_LIFETIME_NANOS = TimeUnit.SECONDS.toNanos(2);

    private final SimulatedBenchProperties properties;
    private final SimulatedFaultSwitches faults;
    private final double stiffnessNewtonPerMm;
    private final double breakForceNewton;

    /** Held for the frozen-value fault, which must repeat one BIT-IDENTICAL float, not a similar one. */
    private volatile float lastEmittedForce = 0f;

    /** Written by drive handles, read by the tick thread. */
    private volatile int setpointRpm = 0;
    private volatile boolean generalEnabled = false;
    private volatile boolean started = false;
    /** True is release (force falls), false is pull (force rises) — matches {@code cfw11Release}/{@code cfw11Pull}. */
    private volatile boolean directionForward = true;

    private volatile double measuredRpm = 0;
    private volatile double positionMm = 0;
    private volatile boolean fractured = false;
    /** Tracked only to spot the rising edge that means "new run, new specimen". */
    private boolean wasEnergized = false;

    private final Set<SimulatedLoadCellStream> streams = new CopyOnWriteArraySet<>();

    /** nanoTime of the most recent {@link #register}; the age both reconnect faults are keyed on. */
    private volatile long lastStreamRegisteredNanos = System.nanoTime();

    private volatile Thread tickThread;
    private volatile boolean running = false;

    public SimulatedBench(SimulatedBenchProperties properties, SimulatedFaultSwitches faults) {
        this.properties = properties;
        this.faults = faults;

        MaterialPreset preset = MaterialPreset.find(properties.getMaterial());
        this.stiffnessNewtonPerMm = preset == null
                ? properties.getStiffnessNewtonPerMm() : preset.stiffnessNewtonPerMm();
        this.breakForceNewton = preset == null
                ? properties.getBreakForceNewton() : preset.breakForceNewton();
        if (preset != null) {
            logger.info("Simulated bench using material preset '{}'", preset.materialName());
        }
    }

    @PostConstruct
    void start() {
        running = true;
        tickThread = new Thread(this::tickLoop, "simulated-bench");
        tickThread.setDaemon(true);
        tickThread.start();
        logger.warn("SIMULATED HARDWARE: plant model running (stiffness {} N/mm, break {} N, model {})."
                        + " Force traces produced in this mode are shape-plausible, NOT calibrated.",
                stiffnessNewtonPerMm, breakForceNewton, properties.getSampleModel());
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
     * Discards the specimen: travel back to zero, fracture healed. Called from two independent
     * triggers because neither alone suffices — see the plant-model section of
     * {@code doc/06-feature-work/virtual-devices/README.md}, which points back here for the one
     * case both triggers miss: a load cell held open across two runs while a failed stop left the
     * drive energized. The second run then inherits the first one's specimen — a fidelity loss
     * confined to simulated mode, never a corruption of real data.
     */
    void mountNewSpecimen(String reason) {
        // Defence in depth for a caller that gets here mid-run: remounting under a live test teleports
        // the crosshead to zero and fails every simulated resume's drift gate. The precise fix is
        // upstream in SimulatedLoadCellStreamProvider#open(boolean), which skips a reconnect's remount.
        if (generalEnabled || measuredRpm != 0) {
            logger.info("Simulated bench: keeping the current specimen, the machine is running ({})", reason);
            return;
        }

        if (positionMm == 0 && !fractured) {
            return;
        }
        positionMm = 0;
        fractured = false;
        lastEmittedForce = 0f;
        logger.info("Simulated bench: new specimen mounted ({})", reason);
    }

    void register(SimulatedLoadCellStream stream) {
        // Stamped so the two reconnect faults below can act on stream AGE. Both need "shortly after
        // this stream came up", which is the only thing distinguishing a reconnect from a run that
        // never lost its sensor - the bench is not told which open was which.
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

    /** The MEASURED shaft speed. Never echo the setpoint here — that unexercises the whole ladder. */
    int measuredRpm() {
        return (int) Math.round(measuredRpm);
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
            // Measured, not assumed from the tick interval: a descheduled tick must still advance
            // the plant by the time that actually passed.
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

        // Pull (directionForward == false) takes up slack and raises force; release pays it back.
        double travel = rpm * properties.getMmPerRev() * dtSeconds / 60.0;
        double position = positionMm + (directionForward ? -travel : travel);
        positionMm = Math.max(0, position);

        emit(forceFor(positionMm));
    }

    /**
     * Turns the plant's force into the sample the streams see, applying whichever load-cell fault is
     * armed. Injected here rather than inside the stream so every subscriber sees one consistent
     * view of the same broken sensor.
     */
    private void emit(double plantForce) {
        if (faults.isActive(SimulatedFault.LOAD_CELL_STREAM_DEATH)) {
            // -800 is CommandExecutionException.NON_NUMERIC_VALUE: the trip reason must name a code
            // that exists in the real driver's table.
            var cause = new StreamFailure("-800", "CommandExecutionException",
                    "simulated driver fault: non-numeric value returned while the driver reported success");
            for (SimulatedLoadCellStream stream : streams) {
                stream.fail(cause);
            }
            return;
        }

        if (faults.isActive(SimulatedFault.LOAD_CELL_FLAPPING)
                && System.nanoTime() - lastStreamRegisteredNanos >= FLAPPING_STREAM_LIFETIME_NANOS) {
            // Same driver code as LOAD_CELL_STREAM_DEATH and for the same reason: a trip reason must
            // name an entry that exists in the driver's table. Fires once per stream - fail()
            // unregisters it, so nothing dies again until a reconnect registers a replacement.
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
            // No dither, and the previous sample verbatim: the detector compares raw float bits, so
            // anything recomputed here would differ in its low bits and never trip.
            force = lastEmittedForce;
        } else if (faults.isActive(SimulatedFault.LOAD_CELL_NAN)) {
            force = Float.NaN;
        } else if (faults.isActive(SimulatedFault.LOAD_CELL_IMPLAUSIBLE_FORCE)) {
            force = 1_000_000f;
        } else {
            force = (float) (plantForce + dither() + reconnectGarbageOffset());
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
            return 0;
        }

        double extension = Math.max(0, position - properties.getSlackMm());
        double force = stiffnessNewtonPerMm * extension;

        if (properties.getSampleModel() == SimulatedBenchProperties.SampleModel.ELASTIC_YIELD_FRACTURE) {
            double yield = properties.getYieldForceNewton();
            if (force > yield) {
                force = yield + (force - yield) * properties.getPostYieldStiffnessFactor();
            }
            if (force >= breakForceNewton) {
                fractured = true;
                logger.warn("SIMULATED HARDWARE: specimen fractured at {} N", breakForceNewton);
                return 0;
            }
        }

        return Math.min(force, properties.getMaxForceNewton());
    }

    /** Applied AFTER the zero clamp, or an unloaded specimen trips the frozen-sample detector. */
    private double dither() {
        double amplitude = properties.getDitherNewton();
        return amplitude <= 0 ? 0 : ThreadLocalRandom.current().nextDouble(-amplitude, amplitude);
    }
}
