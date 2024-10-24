package ch.rupfizupfi.deck.data;

import ch.rupfizupfi.deck.data.jsonViews.Views;
import ch.rupfizupfi.deck.data.serializer.OwnerSerializer;
import ch.rupfizupfi.deck.security.DataWithOwner;
import com.fasterxml.jackson.annotation.JsonView;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import jakarta.annotation.Nullable;
import jakarta.persistence.*;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

import java.util.List;

@Entity
@Table(name = "sample")
public class Sample extends AbstractEntity implements DataWithOwner {
    @ManyToOne(fetch = FetchType.EAGER, optional = true)
    @JsonSerialize(using = OwnerSerializer.class)
    public User owner;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    public Project project;

    public String name;

    @Column(columnDefinition = "TEXT")
    public String description;

    public String model;

    public String manufacturer;

    @Min(value = 1900, message = "Year of manufacture must be between 1900 and 2900")
    @Max(value = 2900, message = "Year of manufacture must be between 1900 and 2900")
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

    @Nullable
    public User getOwner() {
        return owner;
    }

    @JsonView(Views.Simple.class)
    public Long getId() {
        return super.getId();
    }

    @JsonView(Views.Simple.class)
    public String getName() {
        return name;
    }
}
