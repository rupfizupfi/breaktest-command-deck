package ch.rupfizupfi.deck.services;

import ch.rupfizupfi.deck.data.TestParameter;
import ch.rupfizupfi.deck.data.TestParameterRepository;
import com.vaadin.hilla.BrowserCallable;
import com.vaadin.hilla.crud.CrudRepositoryService;
import jakarta.annotation.security.PermitAll;

@BrowserCallable
@PermitAll
public class TestParameterService extends CrudRepositoryService<TestParameter, Long, TestParameterRepository> {
}
