package ch.rupfizupfi.deck.service;

import ch.rupfizupfi.deck.data.FileMetadata;
import ch.rupfizupfi.deck.data.FileMetadataRepository;
import ch.rupfizupfi.deck.filesystem.StorageLocationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;

@Service
public class FileService {
    private static final Logger log = LoggerFactory.getLogger(FileService.class);

    private final FileMetadataRepository fileMetadataRepository;

    private final StorageLocationService storageLocationService;

    public FileService(FileMetadataRepository fileMetadataRepository, StorageLocationService storageLocationService) {
        this.fileMetadataRepository = fileMetadataRepository;
        this.storageLocationService = storageLocationService;
    }

    /** The upload directory, created if absent; an uncreatable one is an {@link IOException}, never a returned path. */
    protected Path getStorageLocation() throws IOException {
        var storageLocation = storageLocationService.getUploadLocation();
        if (!Files.exists(storageLocation)) {
            Files.createDirectories(storageLocation);
        }
        return storageLocation;
    }

    public List<FileMetadata> saveFiles(MultipartFile[] files) throws IOException {
        return List.of(files).stream().map(file -> {
            try {
                return saveFile(file);
            } catch (IOException ex) {
                throw new RuntimeException("Could not store file " + file.getOriginalFilename() + ". Please try again!", ex);
            }
        }).collect(Collectors.toList());
    }

    /**
     * Stores the bytes and returns the row describing them. A row survives only if its bytes do:
     * the first save mints the id {@link #generateFileName} needs, and a failed store removes it
     * again.
     */
    public FileMetadata saveFile(MultipartFile file) throws IOException {
        Path storageLocation = getStorageLocation();
        FileMetadata savedMetadata = fileMetadataRepository.save(new FileMetadata(file.getOriginalFilename()));

        try {
            Path targetLocation = storageLocation.resolve(generateFileName(file, savedMetadata));
            Files.copy(file.getInputStream(), targetLocation);
            savedMetadata.setFilePath(targetLocation.getFileName().toString());
            fileMetadataRepository.save(savedMetadata);
        } catch (IOException | RuntimeException ex) {
            try {
                fileMetadataRepository.delete(savedMetadata);
            } catch (RuntimeException undoFailure) {
                ex.addSuppressed(undoFailure);
            }
            log.warn("Discarded file metadata {} after failing to store {}",
                    savedMetadata.getId(), file.getOriginalFilename(), ex);
            throw ex;
        }

        return savedMetadata;
    }

    protected String generateFileName(MultipartFile file, FileMetadata metadata) {
        return metadata.getId() + "-" + file.getOriginalFilename();
    }


    public Resource loadFileAsResource(String fileName) {
        try {
            Path filePath = resolveWithin(this.getStorageLocation(), fileName);
            Resource resource = new UrlResource(filePath.toUri());
            if (resource.exists()) {
                return resource;
            } else {
                throw new RuntimeException("File not found " + fileName);
            }
        } catch (IOException ex) {
            throw new RuntimeException("File not found " + fileName, ex);
        }
    }

    public boolean deleteFile(String fileName) {
        try {
            Path filePath = resolveWithin(this.getStorageLocation(), fileName);
            Files.delete(filePath);
            return true;
        } catch (IOException ex) {
            throw new RuntimeException("Could not delete file " + fileName, ex);
        }
    }

    /** A caller-supplied file name resolves inside the upload directory or not at all. */
    private static Path resolveWithin(Path base, String name) {
        Path resolved = base.resolve(name).normalize();
        if (!resolved.startsWith(base.normalize())) {
            throw new SecurityException("File name escapes its directory: " + name);
        }
        return resolved;
    }
}