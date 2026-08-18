package ch.rupfizupfi.deck.device.simulated;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Plant-model parameters for {@link SimulatedBench}, all under {@code deck.simulated}.
 * <p>
 * <b>Every number here is invented.</b> Nobody has read stiffness, slack or break force off a real
 * force trace, so simulated curves are shape-plausible, not calibrated — they must never be used to
 * judge whether a real result looks right. The material presets are ordered by intuition about the
 * gear in question, nothing more.
 */
@Component
@ConfigurationProperties("deck.simulated")
public class SimulatedBenchProperties {

    /** Plant integration step. The load cell reader polls every 20 ms, so this gives it ~4 samples. */
    private long tickMillis = 5;

    /** Crosshead travel per motor revolution. Matches the deck's own speed→rpm divisor of 0.375. */
    private double mmPerRev = 0.375;

    /** Free travel before the specimen takes any load. */
    private double slackMm = 5.0;

    /** Elastic spring rate, N/mm. */
    private double stiffnessNewtonPerMm = 2000;

    /** Above this the specimen work-hardens at a reduced rate instead of failing immediately. */
    private double yieldForceNewton = 18_000;

    /** Slope multiplier applied between yield and break. */
    private double postYieldStiffnessFactor = 0.25;

    /** Specimen fails here and force collapses permanently. Only reachable in the fracture model. */
    private double breakForceNewton = 25_000;

    private SampleModel sampleModel = SampleModel.ELASTIC_YIELD_FRACTURE;

    /**
     * Optional preset name overriding stiffness and break force, matched case-insensitively against
     * the seeded `material` rows. Empty means "use the values above".
     */
    private String material = "";

    /**
     * Peak-to-peak noise added to every sample, in newton. Load-bearing, not decoration: a live
     * strain gauge always dithers, and {@code LoadCellThread}'s frozen detector trips on 100
     * bit-identical samples — which a clamped-to-zero force would produce within two seconds.
     */
    private double ditherNewton = 0.5;

    /** Ramp rate toward the commanded speed while energized. */
    private double rampRpmPerSecond = 600;

    /**
     * Deceleration once de-energized. Deliberately a real coast, never a snap to zero: the
     * coast-down is what makes tier 1's "still coasting" branch and the tier 2/3 ladder reachable.
     * Fast enough that a normal cleanup still verifies inside the 5 s deadline.
     */
    private double coastRpmPerSecond = 400;

    /** Hard ceiling, well under LoadCellThread's 450 kN implausibility bound. A model guard, not physics. */
    private double maxForceNewton = 280_000;

    public enum SampleModel {
        /** Hookean forever — a cyclic run never terminates on its own. */
        ELASTIC,
        /** Elastic, then work-hardening, then fracture at {@code breakForceNewton}. */
        ELASTIC_YIELD_FRACTURE
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

    public double getSlackMm() {
        return slackMm;
    }

    public void setSlackMm(double slackMm) {
        this.slackMm = slackMm;
    }

    public double getStiffnessNewtonPerMm() {
        return stiffnessNewtonPerMm;
    }

    public void setStiffnessNewtonPerMm(double stiffnessNewtonPerMm) {
        this.stiffnessNewtonPerMm = stiffnessNewtonPerMm;
    }

    public double getYieldForceNewton() {
        return yieldForceNewton;
    }

    public void setYieldForceNewton(double yieldForceNewton) {
        this.yieldForceNewton = yieldForceNewton;
    }

    public double getPostYieldStiffnessFactor() {
        return postYieldStiffnessFactor;
    }

    public void setPostYieldStiffnessFactor(double postYieldStiffnessFactor) {
        this.postYieldStiffnessFactor = postYieldStiffnessFactor;
    }

    public double getBreakForceNewton() {
        return breakForceNewton;
    }

    public void setBreakForceNewton(double breakForceNewton) {
        this.breakForceNewton = breakForceNewton;
    }

    public SampleModel getSampleModel() {
        return sampleModel;
    }

    public void setSampleModel(SampleModel sampleModel) {
        this.sampleModel = sampleModel;
    }

    public String getMaterial() {
        return material;
    }

    public void setMaterial(String material) {
        this.material = material;
    }

    public double getDitherNewton() {
        return ditherNewton;
    }

    public void setDitherNewton(double ditherNewton) {
        this.ditherNewton = ditherNewton;
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

    public double getMaxForceNewton() {
        return maxForceNewton;
    }

    public void setMaxForceNewton(double maxForceNewton) {
        this.maxForceNewton = maxForceNewton;
    }
}
