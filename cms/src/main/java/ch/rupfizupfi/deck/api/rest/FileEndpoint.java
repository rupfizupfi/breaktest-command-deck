package ch.rupfizupfi.deck.api.rest;

import ch.rupfizupfi.deck.api.services.FileMetadataService;
import ch.rupfizupfi.deck.data.FileMetadata;
import ch.rupfizupfi.deck.service.FileService;
import com.vaadin.flow.server.auth.AnonymousAllowed;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

@RestController
@RequestMapping("/api/files")
@AnonymousAllowed
public class FileEndpoint {
    private static final Logger logger = LoggerFactory.getLogger(FileEndpoint.class);

    @Autowired
    private FileService fileService;

    @Autowired
    private FileMetadataService fileMetadataService;

    @PostMapping("/upload")
    public ResponseEntity<FileMetadata> uploadFile(@RequestParam("file") MultipartFile file) throws IOException {
        logger.info("Received request to upload a single file: {}", file.getOriginalFilename());
        FileMetadata fileMetadata = fileService.saveFile(file);
        logger.info("Successfully uploaded file: {}", fileMetadata.getFileName());
        return ResponseEntity.ok(fileMetadata);
    }

    /**
     * Serves stored bytes only through a {@link FileMetadata} row the caller may read
     * ({@link FileMetadataService#findReadable}). No row, a row of another owner and a row whose
     * bytes are gone all answer 404, so a guessed {@code <id>.<ext>} name discloses nothing.
     */
    @GetMapping("/image/{fileName}")
    public ResponseEntity<Resource> downloadFile(@PathVariable String fileName) {
        if (fileMetadataService.findReadable(fileName).isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        Resource file;
        try {
            file = fileService.loadFileAsResource(fileName);
        } catch (RuntimeException missing) {
            logger.warn("File metadata names {} but its bytes could not be read", fileName, missing);
            return ResponseEntity.notFound().build();
        }

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + file.getFilename() + "\"")
                .body(file);
    }
}