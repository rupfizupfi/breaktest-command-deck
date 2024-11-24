package ch.rupfizupfi.deck.api.services;

import ch.rupfizupfi.deck.data.FileMetadata;
import ch.rupfizupfi.deck.data.FileMetadataRepository;
import ch.rupfizupfi.deck.hilla.crud.CrudRepositoryService;
import com.vaadin.hilla.BrowserCallable;
import jakarta.annotation.security.PermitAll;

/**
 * @TODO implement security
 */
@BrowserCallable
@PermitAll
public class FileMetadataService extends CrudRepositoryService<FileMetadata, FileMetadataRepository>  {
}
