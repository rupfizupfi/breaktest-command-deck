package ch.rupfizupfi.deck.data;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface FileMetadataRepository extends JpaRepository<FileMetadata, Long>, JpaSpecificationExecutor<FileMetadata> {

}