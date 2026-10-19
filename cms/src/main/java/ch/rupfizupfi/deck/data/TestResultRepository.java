package ch.rupfizupfi.deck.data;

import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.Collection;
import java.util.List;

public interface TestResultRepository extends JpaRepository<TestResult, Long>, JpaSpecificationExecutor<TestResult> {
    /**
     * Results whose sample belongs to the project. Unscoped: callers serving a user AND the owner
     * criteria on ({@code OwnerDataHelper#addOwnerCriteriaToSpec}), as the results export does.
     */
    static Specification<TestResult> inProject(long projectId) {
        return (root, query, criteriaBuilder) ->
                criteriaBuilder.equal(root.get("sample").get("project").get("id"), projectId);
    }

    /**
     * Orphan sweep on boot. Rows with a null status (predating the column) never match {@code IN}.
     */
    List<TestResult> findByRunStatusIn(Collection<RunStatus> statuses);
}
