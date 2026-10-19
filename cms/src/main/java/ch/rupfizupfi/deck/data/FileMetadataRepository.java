package ch.rupfizupfi.deck.data;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.Optional;

public interface FileMetadataRepository extends JpaRepository<FileMetadata, Long>, JpaSpecificationExecutor<FileMetadata> {
    /** The row whose stored bytes are named {@code filePath}; {@code FileService} mints that name from the row id, so it is unique. */
    Optional<FileMetadata> findByFilePath(String filePath);
}
