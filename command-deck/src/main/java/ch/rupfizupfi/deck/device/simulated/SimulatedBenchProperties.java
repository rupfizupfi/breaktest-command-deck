package ch.rupfizupfi.deck.device.simulated;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Plant-model parameters for {@link SimulatedBench}, all under {@code deck.simulated}.
 * <p>
 * The specimen comes from a {@link SpecimenProfile} measured off real runs, and so does the sensor
 * noise; the drive's ramp and coast rates are still invented. A simulated curve is a typical run of
 * its profile, never evidence about a real result.
 */
@Component
@ConfigurationProperties("deck.simulated")
public class SimulatedBenchProperties {

    /** Plant integration step. The load cell reader polls every 20 ms, so this gives it ~4 samples. */
    private long tickMillis = 5;

    /** Crosshead travel per motor revolution. Matches the deck's own speed→rpm divisor of 0.375. */
    private double mmPerRev = 0.375;

    /**
     * Forces one specimen profile for every run, matched case-insensitively against a profile key
     * such as {@code stiff}, the enum constant, or a seeded material name ({@code Polyamid}); a
     * name that matches nothing fails startup. Empty, each run picks its profile from its sample's
     * materials through {@link #materialProfiles}.
     */
    private String profile = "";

    /**
     * Material name (as seeded in the {@code material} table, case-insensitive) to profile key.
     * Polyamid is measured; the low-stretch fibres and metals are mapped to {@code stiff} by
     * physics, not by any recorded run. Unmapped materials get {@link SpecimenProfile#DEFAULT}.
     * Keys with spaces or brackets need the bracket form:
     * {@code deck.simulated.material-profiles[Dyneema (UHMW-PE)]=stiff}.
     */
    private Map<String, String> materialProfiles = new LinkedHashMap<>(Map.of(
            "Polyamid", "polyamide-webbing",
            "Polyester", "stiff",
            "Dyneema (UHMW-PE)", "stiff",
            "Kevlar", "stiff",
            "Steel", "stiff",
            "Aluminium", "stiff"));

    /**
     * Draw each mounted specimen's break force and length from the profile's batch scatter. Off,
     * every specimen is the profile's median and every run repeats exactly.
     */
    private boolean scatter = true;

    private SampleModel sampleModel = SampleModel.FRACTURE;

    /**
     * Standard deviation of the Gaussian noise on every sample, in newton: the median over the real
     * runs since run 121, when the cell stopped spiking by ~1 kN. Required: without it a clamped-to-zero force trips
     * {@code MeasurementLoop}'s frozen detector (100 bit-identical samples).
     */
    private double noiseNewton = 10;

    /** Ramp rate toward the commanded speed while energized. */
    private double rampRpmPerSecond = 600;

    /**
     * Deceleration once de-energized. A real coast, not a snap to zero, so tier 1's "still coasting"
     * branch is reachable; fast enough that a normal cleanup verifies inside the 5 s deadline.
     */
    private double coastRpmPerSecond = 400;

    /** Hard ceiling, under the bench's 150 kN rated capacity. A model guard, not physics. */
    private double maxForceNewton = 140_000;

    /**
     * Bus time of one drive transaction, request to reply. The CFW11 answers in 2 to 10 ms; a few
     * ms keep a run's control loop as fast as before while a stop still visibly waits for the
     * transaction ahead of it.
     */
    private long transactionLatencyMillis = 3;

    /** The longest wedge: a day stands for "never ends" and keeps the deadline arithmetic finite. */
    public static final long MAX_WEDGE_MILLIS = 86_400_000;

    /**
     * How long {@link SimulatedFault#DRIVE_TRANSACTION_WEDGED} holds the transaction it takes, at
     * most {@link #MAX_WEDGE_MILLIS} (a larger value is clamped), unless
     * {@link SimulatedBench#endWedge()} releases it first. Above the STOP lane's 1500 ms deadline and
     * above a typical link-loss timeout, so a stop behind it fails and the drive's own watchdog fires
     * first. Above the 1500 ms close bound too, so a {@code close()} during the wedge gives up and
     * the handle closes its port by itself once the wedge ends.
     */
    private long wedgeMillis = 3000;

    public enum SampleModel {
        /** The profile's curve without its break — a cyclic run never terminates on its own. */
        UNBREAKABLE,
        /** The profile's curve up to the specimen's drawn break force, then collapse. */
        FRACTURE
    }

    public long getTickMillis() {
        return tickMillis;
    }

    public void setTickMillis(long tickMillis) {
        this.tickMillis = tickMillis;
    }

    public double getMmPerRev() {
        return mmPerRev;
    }

    public void setMmPerRev(double mmPerRev) {
        this.mmPerRev = mmPerRev;
    }

    public String getProfile() {
        return profile;
    }

    public void setProfile(String profile) {
        this.profile = profile;
    }

    public Map<String, String> getMaterialProfiles() {
        return materialProfiles;
    }

    public void setMaterialProfiles(Map<String, String> materialProfiles) {
        this.materialProfiles = materialProfiles;
    }

    public boolean isScatter() {
        return scatter;
    }

    public void setScatter(boolean scatter) {
        this.scatter = scatter;
    }

    public SampleModel getSampleModel() {
        return sampleModel;
    }

    public void setSampleModel(SampleModel sampleModel) {
        this.sampleModel = sampleModel;
    }

    public double getNoiseNewton() {
        return noiseNewton;
    }

    public void setNoiseNewton(double noiseNewton) {
        this.noiseNewton = noiseNewton;
    }

    public double getRampRpmPerSecond() {
        return rampRpmPerSecond;
    }

    public void setRampRpmPerSecond(double rampRpmPerSecond) {
        this.rampRpmPerSecond = rampRpmPerSecond;
    }

    public double getCoastRpmPerSecond() {
        return coastRpmPerSecond;
    }

    public void setCoastRpmPerSecond(double coastRpmPerSecond) {
        this.coastRpmPerSecond = coastRpmPerSecond;
    }

    public long getTransactionLatencyMillis() {
        return transactionLatencyMillis;
    }

    public void setTransactionLatencyMillis(long transactionLatencyMillis) {
        this.transactionLatencyMillis = transactionLatencyMillis;
    }

    public long getWedgeMillis() {
        return wedgeMillis;
    }

    public void setWedgeMillis(long wedgeMillis) {
        this.wedgeMillis = Math.clamp(wedgeMillis, 0, MAX_WEDGE_MILLIS);
    }

    public double getMaxForceNewton() {
        return maxForceNewton;
    }

    public void setMaxForceNewton(double maxForceNewton) {
        this.maxForceNewton = maxForceNewton;
    }
}
