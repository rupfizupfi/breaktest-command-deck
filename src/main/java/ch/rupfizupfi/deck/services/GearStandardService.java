package ch.rupfizupfi.deck.services;

import ch.rupfizupfi.deck.data.GearStandard;
import ch.rupfizupfi.deck.data.GearStandardRepository;
import com.vaadin.hilla.BrowserCallable;
import com.vaadin.hilla.crud.CrudRepositoryService;
import jakarta.annotation.security.PermitAll;

@BrowserCallable
@PermitAll
public class GearStandardService extends CrudRepositoryService<GearStandard, Long, GearStandardRepository> {
}
