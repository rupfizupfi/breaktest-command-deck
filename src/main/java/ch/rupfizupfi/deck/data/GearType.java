package ch.rupfizupfi.deck.data;

import jakarta.persistence.Entity;
import jakarta.persistence.Table;

@Entity
@Table(name = "gear_type")
public class GearType extends AbstractEntity {
    public String name;
}
