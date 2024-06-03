package ch.rupfizupfi.deck.services;


import ch.rupfizupfi.deck.data.GearType;
import ch.rupfizupfi.deck.data.GearTypeRepository;
import com.vaadin.hilla.BrowserCallable;
import com.vaadin.hilla.crud.CrudRepositoryService;
import jakarta.annotation.security.PermitAll;

@BrowserCallable
@PermitAll
public class GearTypeService extends CrudRepositoryService<GearType, Long, GearTypeRepository> {
}
