package ch.rupfizupfi.deck.services;


import ch.rupfizupfi.deck.data.Sample;
import ch.rupfizupfi.deck.data.SampleRepository;
import com.vaadin.hilla.BrowserCallable;
import com.vaadin.hilla.crud.CrudRepositoryService;
import jakarta.annotation.security.PermitAll;

@BrowserCallable
@PermitAll
public class SampleService extends CrudRepositoryService<Sample, Long, SampleRepository> {
}
