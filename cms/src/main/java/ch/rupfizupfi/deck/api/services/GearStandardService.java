package ch.rupfizupfi.deck.api.services;

import ch.rupfizupfi.deck.data.GearStandard;
import ch.rupfizupfi.deck.data.GearStandardRepository;
import ch.rupfizupfi.deck.hilla.crud.CrudRepositoryService;
import com.vaadin.hilla.BrowserCallable;
import jakarta.annotation.security.PermitAll;

@BrowserCallable
@PermitAll
public class GearStandardService extends CrudRepositoryService<GearStandard, GearStandardRepository> {
}
