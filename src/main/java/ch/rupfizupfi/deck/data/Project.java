package ch.rupfizupfi.deck.data;

import ch.rupfizupfi.deck.security.DataWithOwner;
import jakarta.annotation.Nullable;
import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;

@Entity
@Table(name = "project")
public class Project extends AbstractEntity implements DataWithOwner {
    @ManyToOne(fetch = FetchType.EAGER, optional = true)
    public User owner;

    @Nullable
    public User getOwner() {
        return owner;
    }

    @NotBlank
    public String name;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    public Customer customer;

    @Column(columnDefinition = "TEXT")
    public String description;
}
