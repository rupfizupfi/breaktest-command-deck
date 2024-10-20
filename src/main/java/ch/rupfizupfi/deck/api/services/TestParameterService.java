package ch.rupfizupfi.deck.api.services;

import ch.rupfizupfi.deck.data.TestParameter;
import ch.rupfizupfi.deck.data.TestParameterRepository;
import ch.rupfizupfi.deck.hilla.crud.CrudRepositoryService;
import ch.rupfizupfi.deck.security.CheckUserCanOnlyAccessOwnData;
import com.vaadin.hilla.BrowserCallable;
import jakarta.annotation.security.PermitAll;

@BrowserCallable
@PermitAll
@CheckUserCanOnlyAccessOwnData
public class TestParameterService extends CrudRepositoryService<TestParameter, TestParameterRepository> {
}
