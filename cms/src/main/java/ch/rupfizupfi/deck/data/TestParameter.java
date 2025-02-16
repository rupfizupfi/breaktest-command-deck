package ch.rupfizupfi.deck.data;

import ch.rupfizupfi.deck.data.jsonViews.Views;
import ch.rupfizupfi.deck.data.serializer.OwnerSerializer;
import ch.rupfizupfi.deck.security.DataWithOwner;
import com.fasterxml.jackson.annotation.JsonView;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import jakarta.annotation.Nullable;
import jakarta.persistence.*;

@Entity
@Table(name = "test_parameter")
public class TestParameter extends AbstractEntity implements DataWithOwner {
    @ManyToOne(fetch = FetchType.EAGER, optional = true)
    @JsonSerialize(using = OwnerSerializer.class)
    public User owner;

    @Nullable
    public User getOwner() {
        return owner;
    }

    @JsonView(Views.Simple.class)
    public String type;

    @JsonView(Views.Simple.class)
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

    @JsonView(Views.Simple.class)
    public Long getId() {
        return super.getId();
    }

    @Transient
    @JsonView(Views.Simple.class)
    public String getLabel() {
        return type + " " + speed + " mm/min " + lowerShutOffThreshold + "kN - " + upperShutOffThreshold + "kN";
    }
}
