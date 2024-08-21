package ch.rupfizupfi.deck.data;

import jakarta.annotation.Nullable;
import jakarta.persistence.*;

@Entity
@Table(name = "test_parameter")
public class TestParameter extends AbstractEntity {
    public String type;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    public Sample sample;

    public int speed;

    @Nullable
    public double upperShutOffThreshold;

    @Nullable
    public double lowerShutOffThreshold;

    @Nullable
    public double upperTurnForce;

    @Nullable
    public double lowerTurnForce;

    @Nullable
    public int cycleCount;

    @Nullable
    public double startRampSeconds;

    @Nullable
    public double stopRampSeconds;

    @Transient
    public String getLabel() {
        return type + " " + sample.name;
    }
}
