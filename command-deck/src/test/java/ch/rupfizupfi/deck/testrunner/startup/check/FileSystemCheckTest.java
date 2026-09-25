package ch.rupfizupfi.deck.testrunner.startup.check;

import ch.rupfizupfi.deck.filesystem.StorageLocationService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link FileSystemCheck}'s verdicts over the result data location.
 */
class FileSystemCheckTest {

    @TempDir
    Path resultData;

    @Test
    void anUnsetLocationIsRefused() {
        assertThatThrownBy(() -> checkOver(null).execute())
                .isInstanceOf(CheckFailedException.class)
                .hasMessageContaining("Result data location is not set");
    }

    @Test
    void aRegularFileIsRefused() throws IOException {
        Path file = Files.createFile(resultData.resolve("results.csv"));

        assertThatThrownBy(() -> checkOver(file).execute())
                .isInstanceOf(CheckFailedException.class)
                .hasMessageContaining("not a directory");
    }

    @Test
    void aWritableDirectoryPasses() {
        assertThatCode(() -> checkOver(resultData).execute()).doesNotThrowAnyException();
    }

    @Test
    void aDirectoryThatRefusesWritesIsRefused() {
        // Mocked rather than created: File.canWrite() on a directory ignores the ACL that actually
        // denies the write on Windows.
        File readOnly = mock(File.class);
        when(readOnly.isDirectory()).thenReturn(true);
        when(readOnly.canWrite()).thenReturn(false);
        Path path = mock(Path.class);
        when(path.toFile()).thenReturn(readOnly);

        assertThatThrownBy(() -> checkOver(path).execute())
                .isInstanceOf(CheckFailedException.class)
                .hasMessageContaining("Cannot write");
    }

    private static FileSystemCheck checkOver(Path resultDataLocation) {
        StorageLocationService storageLocationService = mock(StorageLocationService.class);
        when(storageLocationService.getResultDataLocation()).thenReturn(resultDataLocation);
        return new FileSystemCheck(storageLocationService);
    }
}
