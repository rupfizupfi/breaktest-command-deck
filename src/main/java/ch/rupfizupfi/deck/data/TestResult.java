package ch.rupfizupfi.deck.data;

import ch.rupfizupfi.deck.data.serializer.OwnerSerializer;
import ch.rupfizupfi.deck.data.serializer.SimpleSampleSerializer;
import ch.rupfizupfi.deck.data.serializer.SimpleTestParameterSerializer;
import ch.rupfizupfi.deck.security.DataWithOwner;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import jakarta.annotation.Nullable;
import jakarta.persistence.*;

@Entity
@Table(name = "test_result")
public class TestResult extends AbstractEntity implements DataWithOwner {
    @ManyToOne(fetch = FetchType.LAZY, optional = true)
    @JsonSerialize(using = OwnerSerializer.class)
    public User owner;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JsonSerialize(using = SimpleSampleSerializer.class)
    public Sample sample;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JsonSerialize(using = SimpleTestParameterSerializer.class)
    public TestParameter testParameter;

    @Column(columnDefinition = "TEXT")
    public String description;

    @Column(columnDefinition = "TEXT")
    @Nullable
    public String resultText;

    @Nullable
    public User getOwner() {
        return owner;
    }

    @Transient
    public boolean getRun() {
        return false;
    }
}
