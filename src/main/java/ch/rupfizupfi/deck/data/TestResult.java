package ch.rupfizupfi.deck.data;

import ch.rupfizupfi.deck.security.DataWithOwner;
import jakarta.annotation.Nullable;
import jakarta.persistence.*;

@Entity
@Table(name = "test_result")
public class TestResult extends AbstractEntity implements DataWithOwner {
    @ManyToOne(fetch = FetchType.EAGER, optional = true)
    public User owner;

    @Nullable
    public User getOwner() {
        return owner;
    }

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    public Sample sample;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    public TestParameter testParameter;

    @Column(columnDefinition = "TEXT")
    public String description;

    @Column(columnDefinition = "TEXT")
    @Nullable
    public String resultText;

    @Transient
    public boolean getRun() {
        return false;
    }
}
