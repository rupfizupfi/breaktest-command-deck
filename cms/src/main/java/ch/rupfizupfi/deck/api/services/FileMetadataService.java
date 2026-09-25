package ch.rupfizupfi.deck.api.services;

import ch.rupfizupfi.deck.data.FileMetadata;
import ch.rupfizupfi.deck.data.FileMetadataRepository;
import ch.rupfizupfi.deck.data.TestResult;
import ch.rupfizupfi.deck.data.TestResultRepository;
import ch.rupfizupfi.deck.hilla.crud.CrudRepositoryService;
import ch.rupfizupfi.deck.hilla.crud.OwnerDataHelper;
import ch.rupfizupfi.deck.security.UserUtils;
import ch.rupfizupfi.deck.service.FileService;
import com.vaadin.hilla.BrowserCallable;
import org.springframework.lang.Nullable;
import com.vaadin.hilla.crud.filter.Filter;
import jakarta.annotation.security.PermitAll;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Every entry point resolves ownership through the file's {@link TestResult}; a file with no test
 * result, or one whose result has no owner, is readable by everyone. {@link FileMetadata} is not a
 * {@link ch.rupfizupfi.deck.security.DataWithOwner}, hence the plain base class.
 */
@BrowserCallable
@PermitAll
public class FileMetadataService extends CrudRepositoryService<FileMetadata, FileMetadataRepository> {
    @Autowired
    protected TestResultService testResultService;

    @Autowired
    protected TestResultRepository testResultRepository;

    @Autowired
    protected FileService fileService;

    @Autowired
    private OwnerDataHelper ownerDataHelper;

    @Override
    public Optional<FileMetadata> get(Long id) {
        Optional<FileMetadata> stored = super.get(id);
        stored.ifPresent(this::validateAccess);
        return stored;
    }

    /**
     * An id must name a row the caller may read, and a target test result must resolve through the
     * owner-scoped {@link TestResultService#get}.
     */
    @Override
    @Nullable
    public FileMetadata save(FileMetadata value) {
        if (value.getId() != null) {
            this.getRepository().findById(value.getId()).ifPresent(this::validateAccess);
        }

        var testResult = value.getTestResult();
        if (testResult != null && testResult.getId() != null
                && testResultService.get(testResult.getId()).isEmpty()) {
            throw new SecurityException("You do not have permission to access this record");
        }

        return super.save(value);
    }

    @Override
    public List<FileMetadata> saveAll(Iterable<FileMetadata> values) {
        List<FileMetadata> saved = new ArrayList<>();
        values.forEach(value -> saved.add(this.save(value)));
        return saved;
    }

    @Override
    public void delete(Long id) {
        this.getRepository().findById(id).ifPresent(fileMetadata -> {
            validateAccess(fileMetadata);
            if (fileMetadata.getFilePath() != null) {
                fileService.deleteFile(fileMetadata.getFilePath());
            }
        });
        super.delete(id);
    }

    /**
     * A batch is checked whole and refused whole: no row and no stored bytes go before the last id
     * has passed {@link #validateAccess}.
     */
    @Override
    public void deleteAll(Iterable<Long> ids) {
        for (Long id : ids) {
            this.getRepository().findById(id).ifPresent(this::validateAccess);
        }
        ids.forEach(this::delete);
    }

    @Override
    public List<FileMetadata> list(Pageable pageable, @Nullable Filter filter) {
        if (UserUtils.isAdmin()) {
            return super.list(pageable, filter);
        }

        Specification<FileMetadata> spec = this.toSpec(filter);

        spec = spec.and((root, query, criteriaBuilder) -> {
            Subquery<Long> subquery = query.subquery(Long.class);
            Root<TestResult> subRoot = subquery.from(TestResult.class);
            subquery.select(subRoot.get("id"))
                    .where(ownerDataHelper.buildOwnerQuery(subRoot, criteriaBuilder));

            return criteriaBuilder.or(
                    criteriaBuilder.isNull(root.get("testResult")),
                    root.get("testResult").get("id").in(subquery)
            );
        });

        return this.getRepository().findAll(spec, pageable).getContent();
    }

    /**
     * Re-parents a stored row the caller may access; only the test result changes, the rest of the
     * payload is ignored. False when either the file metadata or the test result does not resolve.
     */
    public boolean connectToTestResult(FileMetadata fileMetadata, long testResultId) {
        if (fileMetadata.getId() == null) {
            return false;
        }

        var stored = getRepository().findById(fileMetadata.getId());
        if (stored.isEmpty()) {
            return false;
        }

        var testResult = testResultService.get(testResultId);
        if (testResult.isEmpty()) {
            return false;
        }

        validateAccess(stored.get());
        stored.get().setTestResult(testResult.get());
        getRepository().save(stored.get());
        return true;
    }

    protected void validateAccess(FileMetadata fileMetadata) {
        if (UserUtils.isAdmin()) {
            return;
        }

        if (fileMetadata.getTestResult() == null || fileMetadata.getTestResult().getOwner() == null) {
            return;
        }

        var owner = fileMetadata.getTestResult().getOwner();
        if (!owner.equals(ownerDataHelper.getAuthenticatedUser())) {
            throw new SecurityException("You do not have permission to access this record");
        }
    }
}
