package ch.rupfizupfi.deck.service;

import ch.rupfizupfi.deck.data.FileMetadata;
import ch.rupfizupfi.deck.data.FileMetadataRepository;
import ch.rupfizupfi.deck.filesystem.StorageLocationService;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.net.MalformedURLException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.logging.Logger;
import java.util.stream.Collectors;

@Service
public class FileService {
    private static final Logger log = Logger.getLogger(FileService.class.getName());

    private final FileMetadataRepository fileMetadataRepository;

    private final StorageLocationService storageLocationService;

    public FileService(FileMetadataRepository fileMetadataRepository, StorageLocationService storageLocationService) throws IOException {
        this.fileMetadataRepository = fileMetadataRepository;
        this.storageLocationService = storageLocationService;
    }

    protected Path getStorageLocation() {
        var storageLocation = storageLocationService.getUploadLocation();
        if (!Files.exists(storageLocation)) {
            try {
                Files.createDirectories(storageLocation);
            } catch (IOException ex) {
                log.severe("Could not create the directory where the uploaded files will be stored.");
            }
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

    public FileMetadata saveFile(MultipartFile file) throws IOException {
        FileMetadata metadata = new FileMetadata(file.getOriginalFilename());
        FileMetadata savedMetadata = fileMetadataRepository.save(metadata);

        Path targetLocation = getStorageLocation().resolve(generateFileName(file, savedMetadata));
        Files.copy(file.getInputStream(), targetLocation);
        savedMetadata.setFilePath(targetLocation.getFileName().toString());

        return savedMetadata;
    }

    protected String generateFileName(MultipartFile file, FileMetadata metadata) {
        return metadata.getId() + "-" + file.getOriginalFilename();
    }


    public Resource loadFileAsResource(String fileName) {
        try {
            Path filePath = this.getStorageLocation().resolve(fileName).normalize();
            Resource resource = new UrlResource(filePath.toUri());
            if (resource.exists()) {
                return resource;
            } else {
                throw new RuntimeException("File not found " + fileName);
            }
        } catch (MalformedURLException ex) {
            throw new RuntimeException("File not found " + fileName, ex);
        }
    }

    public boolean deleteFile(String fileName) {
        try {
            Path filePath = this.getStorageLocation().resolve(fileName).normalize();
            Files.delete(filePath);
            return true;
        } catch (IOException ex) {
            throw new RuntimeException("Could not delete file " + fileName, ex);
        }
    }
}