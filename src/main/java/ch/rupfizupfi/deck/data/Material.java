package ch.rupfizupfi.deck.data;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

@Entity
@Table(name = "material")
public class Material extends AbstractEntity {
    public String name;

    @Column(columnDefinition = "TEXT")
    public String description;
}
