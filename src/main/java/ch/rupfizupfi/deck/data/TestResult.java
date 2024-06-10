package ch.rupfizupfi.deck.data;

import jakarta.persistence.*;

@Entity
@Table(name = "test_result")
public class TestResult extends AbstractEntity {
    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    public TestParameter testParameter;

    @Column(columnDefinition = "TEXT")
    public String description;
}
