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
import com.vaadin.hilla.Nullable;
import com.vaadin.hilla.crud.filter.Filter;
import jakarta.annotation.security.PermitAll;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;

import java.util.List;

/**
 * @TODO implement security
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
    public void delete(Long id) {
        this.getRepository().findById(id).ifPresent(fileMetadata -> {
            validateAccess(fileMetadata);
            fileService.deleteFile(fileMetadata.getFilePath());
        });
        super.delete(id);
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

    public boolean connectToTestResult(FileMetadata fileMetadata, long testResultId) {
        var testResult = testResultService.get(testResultId);
        if (testResult.isEmpty()) {
            return false;
        }
        fileMetadata.setTestResult(testResult.get());
        getRepository().save(fileMetadata);
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
