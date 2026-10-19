package ch.rupfizupfi.deck.data;

import ch.rupfizupfi.deck.data.serializer.OwnerSerializer;
import ch.rupfizupfi.deck.security.DataWithOwner;
import jakarta.annotation.Nullable;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Pattern;
import tools.jackson.databind.annotation.JsonSerialize;

@Entity
@Table(name = "customer")
public class Customer extends AbstractEntity implements DataWithOwner {
    /** Null = shared with every user; a shared project may only reference a shared customer. */
    @ManyToOne(fetch = FetchType.EAGER, optional = true)
    @JsonSerialize(using = OwnerSerializer.class)
    public User owner;

    @Nullable
    public User getOwner() {
        return owner;
    }

    public String organization;
    public String firstname;
    public String lastname;

    @Email
    public String email;
    public String street;

    @Pattern(regexp = "^\\d{4,5}$", message = "Invalid Postal code")
    public String code;
    public String location;
    public String country;

    @Transient
    public String getLabel() {
        return organization + " " + firstname + " " + lastname;
    }
}
