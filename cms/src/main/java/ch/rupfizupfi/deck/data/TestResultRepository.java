package ch.rupfizupfi.deck.data;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface TestResultRepository extends JpaRepository<TestResult, Long>, JpaSpecificationExecutor<TestResult> {
    @Query("SELECT result FROM TestResult result INNER JOIN result.sample sample INNER JOIN sample.project project WHERE project.id = :projectId")
    List<TestResult> findByProjectId(@Param("projectId") Long projectId);
}
