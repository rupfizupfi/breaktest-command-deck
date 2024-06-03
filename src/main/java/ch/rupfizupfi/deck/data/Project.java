package ch.rupfizupfi.deck.data;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;

@Entity
@Table(name = "project")
public class Project extends AbstractEntity {
    @NotBlank
    public String name;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    public Customer customer;

    @Column(columnDefinition = "TEXT")
    public String description;
}
