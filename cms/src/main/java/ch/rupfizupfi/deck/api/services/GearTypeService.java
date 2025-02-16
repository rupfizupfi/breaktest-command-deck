package ch.rupfizupfi.deck.api.services;


import ch.rupfizupfi.deck.data.GearType;
import ch.rupfizupfi.deck.data.GearTypeRepository;
import ch.rupfizupfi.deck.hilla.crud.CrudRepositoryService;
import com.vaadin.hilla.BrowserCallable;
import jakarta.annotation.security.PermitAll;

@BrowserCallable
@PermitAll
public class GearTypeService extends CrudRepositoryService<GearType, GearTypeRepository> {
}
