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
    public Double upperShutOffThreshold;

    @Nullable
    public Double lowerShutOffThreshold;

    @Nullable
    public Double upperTurnForce;

    @Nullable
    public Double lowerTurnForce;

    @Nullable
    public Integer cycleCount;

    @Nullable
    public Double startRampSeconds;

    @Nullable
    public Double stopRampSeconds;

    @Transient
    public String getLabel() {
        return type + " " + sample.name;
    }
}
