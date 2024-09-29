package ch.rupfizupfi.deck.data;

import ch.rupfizupfi.deck.security.DataWithOwner;
import jakarta.annotation.Nullable;
import jakarta.persistence.*;

@Entity
@Table(name = "test_parameter")
public class TestParameter extends AbstractEntity implements DataWithOwner {
    @ManyToOne(fetch = FetchType.EAGER, optional = true)
    public User owner;

    @Nullable
    public User getOwner() {
        return owner;
    }

    public String type;

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
        return type + " " + speed + " m/s";
    }
}
