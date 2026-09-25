package ch.rupfizupfi.deck.data;

import ch.rupfizupfi.deck.data.serializer.OwnerSerializer;
import ch.rupfizupfi.deck.data.serializer.SimpleSampleSerializer;
import ch.rupfizupfi.deck.data.serializer.SimpleTestParameterSerializer;
import ch.rupfizupfi.deck.security.DataWithOwner;
import tools.jackson.databind.annotation.JsonSerialize;
import jakarta.annotation.Nullable;
import jakarta.persistence.*;

import java.util.List;

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

    /**
     * Lifecycle status of the run, {@code null} for every row written before the column existed.
     * <p>
     * Stored by name, not ordinal: an ordinal column would silently reinterpret every historical
     * row the day somebody reorders {@link RunStatus}.
     * <p>
     * Nullable is forced and honest at the same time. The repo runs
     * {@code spring.jpa.hibernate.ddl-auto=update} with no migration files, and {@code update}
     * refuses to add a NOT NULL column to a table that already holds rows; those rows are
     * historical runs whose status is genuinely unknown, so {@code null} is what they mean.
     */
    @Enumerated(EnumType.STRING)
    @Column(length = 32)
    @Nullable
    public RunStatus runStatus;

    /** JSON document of state transitions and applied recovery gates — see {@code TestResultStatusPersister}. Nullable for the same reason as {@link #runStatus}. */
    @Column(columnDefinition = "TEXT")
    @Nullable
    public String interruptionLog;

    @Nullable
    public User getOwner() {
        return owner;
    }

    @Transient
    public boolean getRun() {
        return false;
    }

    @OneToMany(mappedBy = "testResult", cascade = CascadeType.ALL, orphanRemoval = true)
    public List<FileMetadata> files;
}
