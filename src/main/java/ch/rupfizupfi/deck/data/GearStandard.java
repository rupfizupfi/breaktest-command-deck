package ch.rupfizupfi.deck.data;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

@Entity
@Table(name = "gear_standard")
public class GearStandard extends AbstractEntity {
    public String name;

    @Column(columnDefinition = "TEXT")
    public String description;
}
