package ch.rupfizupfi.deck.data;

import jakarta.persistence.*;
import org.springframework.lang.Nullable;

@MappedSuperclass
public abstract class AbstractEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Nullable
    private Long id;

    @Version
    @Nullable
    private int version;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    // No caller, still load-bearing: Jackson binds the private `version` field only because this
    // getter is visible (MapperFeature.INFER_PROPERTY_MUTATORS), and Hilla derives the generated
    // TS from that same introspection. Delete it and `version` leaves both AbstractEntity.ts and
    // the wire, so every save of an already-edited row fails its optimistic-lock check. Nothing
    // in the build catches that — not tsc, not crud-smoke, which never performs an update.
    public int getVersion() {
        return version;
    }

    @Override
    public int hashCode() {
        if (getId() != null) {
            return getId().hashCode();
        }
        return super.hashCode();
    }

    @Override
    public boolean equals(Object obj) {
        if (!(obj instanceof AbstractEntity that)) {
            return false; // null or not an AbstractEntity class
        }
        if (getId() != null) {
            return getId().equals(that.getId());
        }
        return super.equals(that);
    }
}
