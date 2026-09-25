package ch.rupfizupfi.deck.service;

import ch.rupfizupfi.deck.data.FileMetadata;
import ch.rupfizupfi.deck.data.FileMetadataRepository;
import ch.rupfizupfi.deck.filesystem.StorageLocationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * A row and its bytes arrive together or not at all. File names reach {@code FileService} straight
 * from the anonymous {@code /api/files/**} surface, so a name resolving outside the upload
 * directory is refused rather than served or deleted.
 */
class FileServiceTest {

    @TempDir
    Path tempRoot;

    private Path uploadRoot;
    private Path outsideFile;
    private FileMetadataRepository repository;
    private StorageLocationService storageLocationService;
    private FileService service;

    @BeforeEach
    void createService() throws IOException {
        uploadRoot = Files.createDirectories(tempRoot.resolve("uploads"));
        outsideFile = Files.writeString(tempRoot.resolve("secret.txt"), "content");

        repository = mock(FileMetadataRepository.class);
        storageLocationService = mock(StorageLocationService.class);
        when(storageLocationService.getUploadLocation()).thenReturn(uploadRoot);
        service = new FileService(repository, storageLocationService);
    }

    @Test
    void aStoredFileLandsUnderTheUploadRootAndItsRowNamesIt() throws IOException {
        List<String> filePathAtEachSave = new ArrayList<>();
        when(repository.save(any(FileMetadata.class))).thenAnswer(invocation -> {
            FileMetadata row = invocation.getArgument(0);
            row.setId(42L);
            filePathAtEachSave.add(row.getFilePath());
            return row;
        });

        FileMetadata saved = service.saveFile(upload("report.pdf"));

        // The stored name carries the id, so the row is saved once to mint it and once to record it.
        assertThat(filePathAtEachSave).containsExactly(null, "42-report.pdf");
        assertThat(saved.getFilePath()).isEqualTo("42-report.pdf");
        assertThat(uploadRoot.resolve("42-report.pdf")).hasContent("content");
        verify(repository, never()).delete(any(FileMetadata.class));
    }

    @Test
    void anUncreatableUploadDirectoryFailsBeforeAnyRowIsWritten() throws IOException {
        Path blocker = Files.writeString(tempRoot.resolve("blocker"), "a file, not a directory");
        when(storageLocationService.getUploadLocation()).thenReturn(blocker.resolve("uploads"));

        assertThatExceptionOfType(IOException.class)
                .isThrownBy(() -> service.saveFile(upload("report.pdf")));
        verifyNoInteractions(repository);
    }

    @Test
    void aFailedCopyTakesTheMetadataRowBackOut() throws IOException {
        FileMetadata row = new FileMetadata("report.pdf");
        row.setId(42L);
        when(repository.save(any(FileMetadata.class))).thenReturn(row);

        MultipartFile file = mock(MultipartFile.class);
        when(file.getOriginalFilename()).thenReturn("report.pdf");
        when(file.getInputStream()).thenThrow(new IOException("stream closed"));

        assertThatExceptionOfType(IOException.class).isThrownBy(() -> service.saveFile(file));

        verify(repository).delete(row);
        assertThat(uploadRoot).isEmptyDirectory();
    }

    @Test
    void aFailedUndoIsSuppressedOntoTheStoreFailure() throws IOException {
        FileMetadata row = new FileMetadata("report.pdf");
        row.setId(42L);
        when(repository.save(any(FileMetadata.class))).thenReturn(row);
        RuntimeException undoFailure = new RuntimeException("row already gone");
        doThrow(undoFailure).when(repository).delete(row);

        MultipartFile file = mock(MultipartFile.class);
        when(file.getOriginalFilename()).thenReturn("report.pdf");
        IOException storeFailure = new IOException("stream closed");
        when(file.getInputStream()).thenThrow(storeFailure);

        Throwable thrown = catchThrowable(() -> service.saveFile(file));

        assertThat(thrown).isSameAs(storeFailure);
        assertThat(thrown.getSuppressed()).containsExactly(undoFailure);
    }

    @Test
    void aPlainNameLoads() throws IOException {
        Files.writeString(uploadRoot.resolve("1-report.pdf"), "content");

        assertThat(service.loadFileAsResource("1-report.pdf").exists()).isTrue();
    }

    @Test
    void loadingATraversingNameIsRefused() {
        assertThatExceptionOfType(SecurityException.class)
                .isThrownBy(() -> service.loadFileAsResource("../secret.txt"));
    }

    @Test
    void loadingAnAbsoluteNameIsRefused() {
        assertThatExceptionOfType(SecurityException.class)
                .isThrownBy(() -> service.loadFileAsResource(outsideFile.toString()));
    }

    @Test
    void aPlainNameDeletes() throws IOException {
        Path target = Files.writeString(uploadRoot.resolve("1-report.pdf"), "content");

        assertThat(service.deleteFile("1-report.pdf")).isTrue();
        assertThat(target).doesNotExist();
    }

    @Test
    void deletingATraversingNameIsRefused() {
        assertThatExceptionOfType(SecurityException.class)
                .isThrownBy(() -> service.deleteFile("../secret.txt"));
        assertThat(outsideFile).exists();
    }

    @Test
    void deletingAnAbsoluteNameIsRefused() {
        assertThatExceptionOfType(SecurityException.class)
                .isThrownBy(() -> service.deleteFile(outsideFile.toString()));
        assertThat(outsideFile).exists();
    }

    private static MultipartFile upload(String originalFilename) {
        return new MockMultipartFile("file", originalFilename, null, "content".getBytes(StandardCharsets.UTF_8));
    }
}
