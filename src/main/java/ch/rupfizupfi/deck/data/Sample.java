package ch.rupfizupfi.deck.data;

import jakarta.persistence.*;
import jakarta.validation.constraints.Pattern;

import java.util.List;

@Entity
@Table(name = "sample")
public class Sample extends AbstractEntity {
    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    public Project project;

    public String name;

    @Column(columnDefinition = "TEXT")
    public String description;

    public String model;

    public String manufacturer;

    @Pattern(regexp = "^[1-2][0-9]{3}$", message = "Year of manufacture must be a four-digit number between 1000 and 2999")
    public int yearOfManufacture;

    @ManyToMany
    @JoinTable(name = "sample_gear_type")
    public List<GearType> gearTypes;

    @ManyToMany
    @JoinTable(name = "sample_gear_standard")
    public List<GearStandard> gearStandards;

    @ManyToMany
    @JoinTable(name = "sample_material")
    public List<Material> materials;
}
