package ch.rupfizupfi.deck.api.services;

import ch.rupfizupfi.deck.data.Material;
import ch.rupfizupfi.deck.data.MaterialRepository;
import com.vaadin.hilla.BrowserCallable;
import ch.rupfizupfi.deck.hilla.crud.CrudRepositoryService;
import jakarta.annotation.security.PermitAll;

import java.util.Optional;

@BrowserCallable
@PermitAll
public class MaterialService extends CrudRepositoryService<Material, MaterialRepository> {
    @Override
    public Optional<Material> get(Long aLong) {
        return super.get(aLong);
    }
}
