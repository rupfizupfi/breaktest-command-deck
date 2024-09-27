package ch.rupfizupfi.deck.api.services;


import ch.rupfizupfi.deck.data.Sample;
import ch.rupfizupfi.deck.data.SampleRepository;
import ch.rupfizupfi.deck.security.CheckUserCanOnlyAccessOwnData;
import com.vaadin.hilla.BrowserCallable;
import ch.rupfizupfi.deck.hilla.crud.CrudRepositoryService;
import jakarta.annotation.security.PermitAll;

@BrowserCallable
@PermitAll
@CheckUserCanOnlyAccessOwnData
public class SampleService extends CrudRepositoryService<Sample, SampleRepository> {
}
