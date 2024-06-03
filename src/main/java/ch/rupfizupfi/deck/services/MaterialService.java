package ch.rupfizupfi.deck.services;

import ch.rupfizupfi.deck.data.Material;
import ch.rupfizupfi.deck.data.MaterialRepository;
import com.vaadin.hilla.BrowserCallable;
import com.vaadin.hilla.crud.CrudRepositoryService;
import jakarta.annotation.security.PermitAll;

@BrowserCallable
@PermitAll
public class MaterialService extends CrudRepositoryService<Material, Long, MaterialRepository> {
}
