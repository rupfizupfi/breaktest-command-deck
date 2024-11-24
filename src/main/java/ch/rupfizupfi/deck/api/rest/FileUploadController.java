package ch.rupfizupfi.deck.api.rest;

import ch.rupfizupfi.deck.data.FileMetadata;
import ch.rupfizupfi.deck.service.FileService;
import com.vaadin.flow.server.auth.AnonymousAllowed;
import jakarta.annotation.security.PermitAll;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;

@RestController
@RequestMapping("/api/files")
@AnonymousAllowed
public class FileUploadController {
    private static final Logger logger = LoggerFactory.getLogger(FileUploadController.class);

    @Autowired
    private FileService fileService;

    @PostMapping("/uploads")
    public ResponseEntity<List<FileMetadata>> uploadFiles(@RequestParam("files") MultipartFile[] files) throws IOException {
        logger.info("Received request to upload multiple files");
        List<FileMetadata> fileMetadataList = fileService.saveFiles(files);
        logger.info("Successfully uploaded {} files", fileMetadataList.size());
        return ResponseEntity.ok(fileMetadataList);
    }

    @PostMapping("/upload")
    public ResponseEntity<FileMetadata> uploadFile(@RequestParam("file") MultipartFile file) throws IOException {
        logger.info("Received request to upload a single file: {}", file.getOriginalFilename());
        FileMetadata fileMetadata = fileService.saveFile(file);
        logger.info("Successfully uploaded file: {}", fileMetadata.getFileName());
        return ResponseEntity.ok(fileMetadata);
    }
}