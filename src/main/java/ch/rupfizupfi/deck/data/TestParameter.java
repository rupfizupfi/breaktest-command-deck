package ch.rupfizupfi.deck.data;

import jakarta.persistence.*;

@Entity
@Table(name = "test_parameter")
public class TestParameter extends AbstractEntity {
    public String type;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    public Sample sample;

    public int speed;

    public int endConditionForce;

    @Transient
    public String getLabel() {
        return type + " " + sample.name;
    }
}
