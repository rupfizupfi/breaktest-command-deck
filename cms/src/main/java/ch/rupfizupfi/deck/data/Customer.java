package ch.rupfizupfi.deck.data;

import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Pattern;

@Entity
@Table(name = "customer")
public class Customer extends AbstractEntity {
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
