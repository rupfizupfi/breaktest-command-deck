package ch.rupfizupfi.deck.api.services;

import ch.rupfizupfi.deck.data.Project;
import ch.rupfizupfi.deck.data.ProjectRepository;
import ch.rupfizupfi.deck.hilla.crud.CrudRepositoryServiceForOwnerData;
import ch.rupfizupfi.deck.security.CheckUserCanOnlyAccessOwnData;
import com.vaadin.hilla.BrowserCallable;
import ch.rupfizupfi.deck.hilla.crud.CrudRepositoryService;
import jakarta.annotation.security.PermitAll;

@BrowserCallable
@PermitAll
public class ProjectService extends CrudRepositoryServiceForOwnerData<Project, ProjectRepository> {
}
