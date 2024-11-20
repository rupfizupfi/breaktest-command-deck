package ch.rupfizupfi.deck.service;

import ch.rupfizupfi.deck.data.FileMetadata;
import ch.rupfizupfi.deck.data.FileMetadataRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.stream.Collectors;

@Service
public class FileService {

    private final Path fileStorageLocation = Paths.get("uploads").toAbsolutePath().normalize();

    @Autowired
    private FileMetadataRepository fileMetadataRepository;

    public FileService() throws IOException {
        Files.createDirectories(fileStorageLocation);
    }

    public List<FileMetadata> saveFiles(MultipartFile[] files) throws IOException {
        return List.of(files).stream().map(file -> {
            try {
                Path targetLocation = fileStorageLocation.resolve(file.getOriginalFilename());
                Files.copy(file.getInputStream(), targetLocation);
                FileMetadata metadata = new FileMetadata(file.getOriginalFilename(), targetLocation.toString());
                return fileMetadataRepository.save(metadata);
            } catch (IOException ex) {
                throw new RuntimeException("Could not store file " + file.getOriginalFilename() + ". Please try again!", ex);
            }
        }).collect(Collectors.toList());
    }
}