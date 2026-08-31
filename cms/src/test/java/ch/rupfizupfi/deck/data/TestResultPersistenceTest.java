package ch.rupfizupfi.deck.data;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Persistence behaviour of {@link TestResult} and its {@code files} relation against real
 * PostgreSQL — the answer to OQ-32. H2 is deliberately not used: dev/prod is an H2/Postgres split
 * and repository tests written against H2 semantics are the predictable failure
 *
 * <p>Skips (rather than fails) on machines without Docker; CI has it.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers(disabledWithoutDocker = true)
@ActiveProfiles("test")
class TestResultPersistenceTest {

    /**
     * Pinned tag: the compose file's unpinned {@code postgres} gives no version to mirror.
     * No type parameter — Testcontainers 2.x made {@code PostgreSQLContainer} non-generic.
     *
     * <p>{@code @Container} stops the container after this class while the cached Spring context
     * — datasource included — outlives it, so a second Postgres slice test must share this exact
     * configuration (ideally this container), never duplicate it.
     */
    @Container
    @ServiceConnection
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    /**
     * Replaces {@code Application} as the slice's configuration source. Without it the slice walks
     * up to {@code Application} and instantiates its {@code dataSourceScriptDatabaseInitializer}
     * @Bean, whose override calls {@code UserRepository#count()} during bean initialization —
     * before Hibernate has created the schema. {@code spring.sql.init.mode=never} cannot prevent
     * that call, only this can. {@code @EnableAutoConfiguration} keeps entity/repository scanning
     * anchored to this package, where every cms entity lives.
     */
    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    static class SliceConfig {
    }

    @Autowired
    TestEntityManager em;

    @Autowired
    TestResultRepository repository;

    @Autowired
    ApplicationContext context;

    @Test
    void applicationDataSqlInitializerBeanStaysOutOfTheSlice() {
        // Guard for the SliceConfig comment above: if this bean reappears, the slice is loading
        // Application again and the data.sql initializer runs against a schema that may not exist.
        assertThat(context.containsBean("dataSourceScriptDatabaseInitializer")).isFalse();
    }

    @Test
    void savingATestResultCascadesTheInsertToItsFiles() {
        TestResult result = persistedTestResult();
        attachFile(result, "measurements.csv");
        attachFile(result, "report.pdf");

        em.flush();
        em.clear();

        assertThat(fileRowCount()).isEqualTo(2);
        TestResult reloaded = em.find(TestResult.class, result.getId());
        assertThat(reloaded.files)
                .extracting(FileMetadata::getFileName)
                .containsExactlyInAnyOrder("measurements.csv", "report.pdf");
    }

    @Test
    void deletingATestResultDeletesItsFileRows() {
        TestResult result = persistedTestResult();
        attachFile(result, "measurements.csv");
        attachFile(result, "report.pdf");
        em.flush();
        em.clear();

        TestResult reloaded = em.find(TestResult.class, result.getId());
        repository.delete(reloaded);
        em.flush();

        assertThat(fileRowCount()).isZero();
        assertThat(repository.findById(result.getId())).isEmpty();
    }

    @Test
    void removingAFileFromTheCollectionDeletesItsRow() {
        TestResult result = persistedTestResult();
        attachFile(result, "keep.csv");
        attachFile(result, "orphan.csv");
        em.flush();
        em.clear();

        TestResult reloaded = em.find(TestResult.class, result.getId());
        reloaded.files.removeIf(file -> file.getFileName().equals("orphan.csv"));
        em.flush();
        em.clear();

        // orphanRemoval = true holds on Postgres: the removed child's row is gone, not left
        // behind with a null FK.
        assertThat(fileRowCount()).isEqualTo(1);
        assertThat(em.find(TestResult.class, result.getId()).files)
                .extracting(FileMetadata::getFileName)
                .containsExactly("keep.csv");
    }

    @Test
    void findByRunStatusInMatchesOnlyNonTerminalRowsAndSkipsNullStatus() {
        // The StartupRecoveryRunner orphan sweep: crashed processes leave IN_PROGRESS or
        // INTERRUPTED behind; pre-column rows have null status and must stay out of the sweep.
        TestResult inProgress = persistedTestResult();
        inProgress.runStatus = RunStatus.IN_PROGRESS;
        TestResult interrupted = persistedTestResult();
        interrupted.runStatus = RunStatus.INTERRUPTED;
        TestResult completed = persistedTestResult();
        completed.runStatus = RunStatus.COMPLETED;
        TestResult legacyNullStatus = persistedTestResult();
        em.flush();
        em.clear();

        List<TestResult> swept = repository.findByRunStatusIn(List.of(RunStatus.IN_PROGRESS, RunStatus.INTERRUPTED));

        assertThat(swept)
                .extracting(AbstractEntity::getId)
                .containsExactlyInAnyOrder(inProgress.getId(), interrupted.getId());
    }

    @Test
    void findByProjectIdWalksSampleToProjectAndReturnsOnlyThatProjectsResults() {
        // The repository's only hand-written JPQL: TestResult carries no project column, the
        // query joins result -> sample -> project. Each persistedTestResult() builds its own
        // customer/project/sample graph, so the second result is the other-project control.
        TestResult inProject = persistedTestResult();
        TestResult otherProject = persistedTestResult();
        em.flush();
        em.clear();

        List<TestResult> found = repository.findByProjectId(inProject.sample.project.getId());

        assertThat(found)
                .extracting(AbstractEntity::getId)
                .containsExactly(inProject.getId())
                .doesNotContain(otherProject.getId());
    }

    @Test
    void runStatusIsStoredByNameAndReadsBackAsTheSameConstant() {
        TestResult result = persistedTestResult();
        result.runStatus = RunStatus.COMPLETED_WITH_GAPS;
        em.flush();
        em.clear();

        // The raw column value is the constant's name — the property RunStatus's javadoc promises
        // so that reordering the enum can never reinterpret historical rows.
        Object stored = em.getEntityManager()
                .createNativeQuery("select run_status from test_result where id = :id")
                .setParameter("id", result.getId())
                .getSingleResult();
        assertThat(stored).isEqualTo("COMPLETED_WITH_GAPS");
        assertThat(em.find(TestResult.class, result.getId()).runStatus).isEqualTo(RunStatus.COMPLETED_WITH_GAPS);
    }

    private long fileRowCount() {
        return em.getEntityManager()
                .createQuery("select count(f) from FileMetadata f", Long.class)
                .getSingleResult();
    }

    /** The minimal valid graph: sample and testParameter are optional = false, everything else may stay null. */
    private TestResult persistedTestResult() {
        Customer customer = new Customer();
        customer.organization = "Acme Test Rigs";
        em.persist(customer);

        Project project = new Project();
        project.name = "breaktest";
        project.customer = customer;
        em.persist(project);

        Sample sample = new Sample();
        sample.project = project;
        sample.name = "gear-7";
        // Bean validation runs on persist; the int default 0 violates @Min(1900).
        sample.yearOfManufacture = 2024;
        em.persist(sample);

        TestParameter parameter = new TestParameter();
        parameter.type = "destructive";
        parameter.speed = 10;
        em.persist(parameter);

        TestResult result = new TestResult();
        result.sample = sample;
        result.testParameter = parameter;
        result.files = new ArrayList<>();
        em.persist(result);
        return result;
    }

    /** FileMetadata owns the FK (mappedBy on TestResult.files), so both sides must be set by hand. */
    private void attachFile(TestResult result, String fileName) {
        FileMetadata file = new FileMetadata(fileName);
        file.setTestResult(result);
        result.files.add(file);
    }
}
