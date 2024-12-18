package ch.rupfizupfi.deck.data;

import ch.rupfizupfi.deck.data.jsonViews.Views;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonView;
import jakarta.persistence.*;
import org.springframework.lang.Nullable;

import java.util.Set;

@Entity
@Table(name = "application_user")
public class User extends AbstractEntity {
    @JsonView(Views.Simple.class)
    private String username;

    @JsonView(Views.Simple.class)
    private String name;

    @JsonIgnore
    private String hashedPassword;

    @Transient
    @Nullable
    private String newPassword;

    @Enumerated(EnumType.STRING)
    @ElementCollection(fetch = FetchType.EAGER)
    private Set<Role> roles;

    @JsonView(Views.Simple.class)
    public Long getId() {
        return super.getId();
    }

    // Getters and setters
    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getHashedPassword() {
        return hashedPassword;
    }

    public void setHashedPassword(String hashedPassword) {
        this.hashedPassword = hashedPassword;
    }

    public @Nullable String getNewPassword() {
        return newPassword;
    }

    public boolean hasNewPassword() {
        return newPassword != null && !newPassword.isEmpty();
    }

    public void setNewPassword(@Nullable String newPassword) {
        this.newPassword = newPassword;
    }

    public Set<Role> getRoles() {
        return roles;
    }

    public void setRoles(Set<Role> roles) {
        this.roles = roles;
    }
}