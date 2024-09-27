package ch.rupfizupfi.deck.api.services;


import ch.rupfizupfi.deck.data.GearType;
import ch.rupfizupfi.deck.data.GearTypeRepository;
import com.vaadin.hilla.BrowserCallable;
import ch.rupfizupfi.deck.hilla.crud.CrudRepositoryService;
import jakarta.annotation.security.PermitAll;

@BrowserCallable
@PermitAll
public class GearTypeService extends CrudRepositoryService<GearType, GearTypeRepository> {
}
