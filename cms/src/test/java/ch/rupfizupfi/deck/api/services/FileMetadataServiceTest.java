package ch.rupfizupfi.deck.api.services;

import ch.rupfizupfi.deck.data.FileMetadata;
import ch.rupfizupfi.deck.data.FileMetadataRepository;
import ch.rupfizupfi.deck.data.TestResult;
import ch.rupfizupfi.deck.data.User;
import ch.rupfizupfi.deck.hilla.crud.OwnerDataHelper;
import ch.rupfizupfi.deck.service.FileService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Every entry point resolves ownership through the file's test result; an admin reaches all of them. */
class FileMetadataServiceTest {

    private static final long OWN_RESULT_ID = 10L;
    private static final long FOREIGN_RESULT_ID = 20L;
    private static final long OWN_ROW_ID = 1L;
    private static final long FOREIGN_ROW_ID = 2L;

    private FileMetadataRepository repository;
    private TestResultService testResultService;
    private FileService fileService;
    private ScopedFileMetadataService service;

    private FileMetadata ownRow;
    private FileMetadata foreignRow;
    private TestResult foreignResult;

    private static class ScopedFileMetadataService extends FileMetadataService {
        private final FileMetadataRepository repository;

        ScopedFileMetadataService(FileMetadataRepository repository) {
            this.repository = repository;
        }

        @Override
        protected FileMetadataRepository getRepository() {
            return repository;
        }
    }

    @BeforeEach
    void createService() {
        User owner = new User();
        owner.setId(1L);
        User stranger = new User();
        stranger.setId(2L);

        TestResult ownResult = new TestResult();
        ownResult.setId(OWN_RESULT_ID);
        ownResult.owner = owner;

        foreignResult = new TestResult();
        foreignResult.setId(FOREIGN_RESULT_ID);
        foreignResult.owner = stranger;

        ownRow = new FileMetadata("own.png");
        ownRow.setId(OWN_ROW_ID);
        ownRow.setTestResult(ownResult);

        foreignRow = new FileMetadata("foreign.png");
        foreignRow.setId(FOREIGN_ROW_ID);
        foreignRow.setTestResult(foreignResult);

        repository = mock(FileMetadataRepository.class);
        when(repository.findById(OWN_ROW_ID)).thenReturn(Optional.of(ownRow));
        when(repository.findById(FOREIGN_ROW_ID)).thenReturn(Optional.of(foreignRow));
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        OwnerDataHelper ownerDataHelper = mock(OwnerDataHelper.class);
        when(ownerDataHelper.getAuthenticatedUser()).thenReturn(owner);

        testResultService = mock(TestResultService.class);
        when(testResultService.get(OWN_RESULT_ID)).thenReturn(Optional.of(ownResult));
        when(testResultService.get(FOREIGN_RESULT_ID)).thenReturn(Optional.empty());

        fileService = mock(FileService.class);

        service = new ScopedFileMetadataService(repository);
        ReflectionTestUtils.setField(service, "ownerDataHelper", ownerDataHelper);
        ReflectionTestUtils.setField(service, "testResultService", testResultService);
        ReflectionTestUtils.setField(service, "fileService", fileService);

        authenticateAs("ROLE_USER");
    }

    @AfterEach
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void readingARowOfAnotherOwnerIsRefused() {
        assertThatExceptionOfType(SecurityException.class)
                .isThrownBy(() -> service.get(FOREIGN_ROW_ID));
    }

    @Test
    void readingAnOwnRowReturnsIt() {
        assertThat(service.get(OWN_ROW_ID)).contains(ownRow);
    }

    @Test
    void attachingToAResultOfAnotherOwnerIsRefused() {
        FileMetadata payload = new FileMetadata("upload.png");
        payload.setTestResult(foreignResult);

        assertThatExceptionOfType(SecurityException.class).isThrownBy(() -> service.save(payload));
        verify(repository, never()).save(any());
    }

    @Test
    void overwritingARowOfAnotherOwnerIsRefused() {
        FileMetadata payload = new FileMetadata("upload.png");
        payload.setId(FOREIGN_ROW_ID);

        assertThatExceptionOfType(SecurityException.class).isThrownBy(() -> service.save(payload));
        verify(repository, never()).save(any());
    }

    @Test
    void aBatchSaveStopsAtARowOfAnotherOwner() {
        FileMetadata foreignPayload = new FileMetadata("upload.png");
        foreignPayload.setId(FOREIGN_ROW_ID);

        assertThatExceptionOfType(SecurityException.class)
                .isThrownBy(() -> service.saveAll(List.of(new FileMetadata("upload.png"), foreignPayload)));
        verify(repository, never()).save(foreignPayload);
    }

    @Test
    void aBatchDeleteHoldingARowOfAnotherOwnerRemovesNothing() {
        assertThatExceptionOfType(SecurityException.class)
                .isThrownBy(() -> service.deleteAll(List.of(OWN_ROW_ID, FOREIGN_ROW_ID)));
        verify(repository, never()).deleteById(any());
        verifyNoInteractions(fileService);
    }

    @Test
    void aBatchDeleteOfOwnRowsRemovesEachRowAndItsBytes() {
        ownRow.setFilePath("1-own.png");
        FileMetadata secondOwnRow = new FileMetadata("second.png");
        secondOwnRow.setId(3L);
        secondOwnRow.setTestResult(ownRow.getTestResult());
        secondOwnRow.setFilePath("3-second.png");
        when(repository.findById(3L)).thenReturn(Optional.of(secondOwnRow));

        service.deleteAll(List.of(OWN_ROW_ID, 3L));

        verify(repository).deleteById(OWN_ROW_ID);
        verify(repository).deleteById(3L);
        verify(fileService).deleteFile("1-own.png");
        verify(fileService).deleteFile("3-second.png");
    }

    @Test
    void deletingARowWithoutStoredBytesTouchesNoFile() {
        service.delete(OWN_ROW_ID);

        verify(repository).deleteById(OWN_ROW_ID);
        verifyNoInteractions(fileService);
    }

    @Test
    void anAdminReadsAndAttachesAcrossOwners() {
        authenticateAs("ROLE_ADMIN");
        // TestResultService#get resolves every row for an admin.
        when(testResultService.get(FOREIGN_RESULT_ID)).thenReturn(Optional.of(foreignResult));

        assertThat(service.get(FOREIGN_ROW_ID)).contains(foreignRow);

        FileMetadata payload = new FileMetadata("upload.png");
        payload.setTestResult(foreignResult);
        assertThat(service.save(payload)).isSameAs(payload);
        verify(repository).save(payload);
    }

    private void authenticateAs(String role) {
        SecurityContextHolder.getContext()
                .setAuthentication(new TestingAuthenticationToken("tester", "n/a", role));
    }
}
