package ch.rupfizupfi.deck.api.services;

import ch.rupfizupfi.deck.data.FileMetadata;
import ch.rupfizupfi.deck.data.FileMetadataRepository;
import ch.rupfizupfi.deck.data.TestResult;
import ch.rupfizupfi.deck.data.TestResultRepository;
import ch.rupfizupfi.deck.hilla.crud.CrudRepositoryService;
import ch.rupfizupfi.deck.hilla.crud.OwnerDataHelper;
import ch.rupfizupfi.deck.hilla.crud.PageCap;
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
import org.springframework.transaction.annotation.Transactional;

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

    @Override
    public boolean exists(Long id) {
        return this.getRepository().findById(id).filter(this::isAccessible).isPresent();
    }

    /**
     * An id must name a row the caller may read, and a target test result must resolve through the
     * owner-scoped {@link TestResultService#get}. {@code filePath} names the bytes {@link #delete}
     * removes, so it is server-owned: an update keeps the stored path and display name, a new row
     * gets none. {@code testResult} is {@code @JsonIgnore}d, so a browser payload never carries it;
     * an update without one keeps the stored link.
     */
    @Override
    @Nullable
    public FileMetadata save(FileMetadata value) {
        if (value.getId() != null) {
            this.getRepository().findById(value.getId()).ifPresent(stored -> {
                validateAccess(stored);
                value.setFilePath(stored.getFilePath());
                value.setFileName(stored.getFileName());
                if (value.getTestResult() == null) {
                    value.setTestResult(stored.getTestResult());
                }
            });
        } else {
            value.setFilePath(null);
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

        return this.getRepository().findAll(readableBy(this.toSpec(filter)), PageCap.clamp(pageable)).getContent();
    }

    @Override
    public long count(@Nullable Filter filter) {
        if (UserUtils.isAdmin()) {
            return super.count(filter);
        }
        return this.getRepository().count(readableBy(this.toSpec(filter)));
    }

    /** ANDs the read rule of {@link #validateAccess} onto {@code spec} as a query. */
    private Specification<FileMetadata> readableBy(Specification<FileMetadata> spec) {
        return spec.and((root, query, criteriaBuilder) -> {
            Subquery<Long> subquery = query.subquery(Long.class);
            Root<TestResult> subRoot = subquery.from(TestResult.class);
            subquery.select(subRoot.get("id"))
                    .where(ownerDataHelper.buildOwnerQuery(subRoot, criteriaBuilder));

            return criteriaBuilder.or(
                    criteriaBuilder.isNull(root.get("testResult")),
                    root.get("testResult").get("id").in(subquery)
            );
        });
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

    /**
     * The row storing the bytes named {@code filePath}, if the caller may read it under the rule of
     * {@link #validateAccess}; empty when no row names those bytes. The image endpoint serves only
     * what this resolves, so a stored name alone grants nothing.
     */
    @Transactional(readOnly = true)
    public Optional<FileMetadata> findReadable(String filePath) {
        return this.getRepository().findByFilePath(filePath).filter(this::isAccessible);
    }

    protected void validateAccess(FileMetadata fileMetadata) {
        if (!isAccessible(fileMetadata)) {
            throw new SecurityException("You do not have permission to access this record");
        }
    }

    private boolean isAccessible(FileMetadata fileMetadata) {
        if (UserUtils.isAdmin()) {
            return true;
        }

        if (fileMetadata.getTestResult() == null || fileMetadata.getTestResult().getOwner() == null) {
            return true;
        }

        return fileMetadata.getTestResult().getOwner().equals(ownerDataHelper.getAuthenticatedUser());
    }
}
