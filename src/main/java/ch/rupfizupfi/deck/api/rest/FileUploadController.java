package ch.rupfizupfi.deck.api.rest;

import ch.rupfizupfi.deck.data.FileMetadata;
import ch.rupfizupfi.deck.service.FileService;
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
public class FileUploadController {

    @Autowired
    private FileService fileService;

    @PostMapping("/upload")
    public ResponseEntity<List<FileMetadata>> uploadFiles(@RequestParam("files") MultipartFile[] files) throws IOException {
        List<FileMetadata> fileMetadataList = fileService.saveFiles(files);
        return ResponseEntity.ok(fileMetadataList);
    }
}