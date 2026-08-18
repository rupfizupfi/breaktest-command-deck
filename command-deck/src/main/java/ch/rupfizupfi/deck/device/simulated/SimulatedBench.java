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
 * One plant model that the simulated drive writes to and the simulated load cell reads from.
 * <p>
 * They cannot be independent: a cyclic run closes a loop through the hardware — force crosses the
 * upper limit, the runner flips direction on the drive, and the force must actually <i>fall</i> as
 * a result. An independent force generator either never crosses the thresholds or crosses them
 * regardless of the motor, and either way the cycle logic goes untested.
 * <p>
 * All parameters are invented; see {@link SimulatedBenchProperties}.
 */
@Component
@ConditionalOnProperty(name = "deck.hardware.mode", havingValue = "simulated")
public class SimulatedBench {

    private static final Logger logger = LoggerFactory.getLogger(SimulatedBench.class);

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
     * Discards the specimen: travel back to zero, fracture healed. Without it the fracture latch and
     * the accumulated crosshead travel survive into the next run, and every run after a destructive
     * one reads zero force forever — its cyclic loop then never crosses a limit and hangs.
     * <p>
     * Two independent triggers, because neither alone is sufficient. The rising edge of energization
     * misses the case that matters most: a stop that <em>failed</em> never cleared the drive's
     * control bits, so the next run's energize is not an edge at all. Opening a load cell stream
     * covers that, and cannot fire mid-run — the device only opens one when its reference count
     * leaves zero, which happens before the motor is energized.
     * <p>
     * Known limit: if something holds the load cell open across two runs and the drive was left
     * energized by a failed stop, neither trigger fires and the second run inherits the first one's
     * specimen. Fidelity loss confined to simulated mode, never a corruption of real data.
     */
    void mountNewSpecimen(String reason) {
        if (positionMm == 0 && !fractured) {
            return;
        }
        positionMm = 0;
        fractured = false;
        lastEmittedForce = 0f;
        logger.info("Simulated bench: new specimen mounted ({})", reason);
    }

    void register(SimulatedLoadCellStream stream) {
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

    /**
     * The MEASURED shaft speed, which is the whole point of the drive fake: a fake that echoed its
     * own setpoint would mark every stop instantly verified and leave the coast-down, and with it
     * the entire escalation ladder, unexercised.
     */
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
            // Measured, not assumed: a descheduled tick must advance the plant by the time that
            // actually passed, or the force curve silently depends on machine load.
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
        // The drive still answers; only the shaft ignores it. That distinction is the whole reason
        // the escalation is gated on drive responsiveness rather than on the clock.
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
        // Clamped at zero so a long release does not bank negative travel the next pull has to
        // undo before any force appears.
        positionMm = Math.max(0, position);

        emit(forceFor(positionMm));
    }

    /**
     * Turns the plant's force into the sample the streams see, applying whichever load-cell fault is
     * armed. Faults are injected here rather than inside the stream so every subscriber sees one
     * consistent view of the same broken sensor.
     */
    private void emit(double plantForce) {
        if (faults.isActive(SimulatedFault.LOAD_CELL_STREAM_DEATH)) {
            // Terminal, and only once: the driver records a cause and its reader thread is gone.
            var cause = new StreamFailure("14", "CommandExecutionException",
                    "simulated driver fault: non-numeric value from READCOMMAND");
            for (SimulatedLoadCellStream stream : streams) {
                stream.fail(cause);
            }
            return;
        }

        if (faults.isActive(SimulatedFault.LOAD_CELL_SILENT)) {
            // Still "reading" as far as the driver is concerned - the sensor just went quiet, which
            // is precisely the case the deck can only detect as silence.
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
            force = (float) (plantForce + dither());
            lastEmittedForce = force;
        }

        long timestamp = System.currentTimeMillis();
        for (SimulatedLoadCellStream stream : streams) {
            stream.offer(new Measurement(force, timestamp));
        }
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

    /**
     * Applied AFTER the zero clamp, on purpose: an unloaded specimen otherwise emits a bit-identical
     * 0.0 every tick and trips the frozen-sample detector within two seconds.
     */
    private double dither() {
        double amplitude = properties.getDitherNewton();
        return amplitude <= 0 ? 0 : ThreadLocalRandom.current().nextDouble(-amplitude, amplitude);
    }
}
