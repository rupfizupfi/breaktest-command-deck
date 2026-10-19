package ch.rupfizupfi.deck.data;

import com.vaadin.hilla.crud.JpaFilterConverter;
import com.vaadin.hilla.crud.filter.PropertyStringFilter;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Persistence behaviour of {@link TestResult} and its {@code files} relation against PostgreSQL,
 * the production database (OQ-32), on the schema the migrations build. Skips on machines without
 * Docker.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers(disabledWithoutDocker = true)
@ActiveProfiles("test")
class TestResultPersistenceTest {

    /**
     * {@code @Container} stops the container after this class while the cached Spring context
     * outlives it, so another Postgres slice test must share this configuration, not duplicate it.
     */
    @Container
    @ServiceConnection
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18-alpine");

    @Autowired
    TestEntityManager em;

    @Autowired
    TestResultRepository repository;

    @Autowired
    TestParameterRepository testParameters;

    @Autowired
    Flyway flyway;

    @Test
    void flywayBuiltTheSchemaFromV1RatherThanBaselining() {
        // Hibernate validated this schema at startup; that proves V1 only if Flyway ran it.
        assertThat(flyway.info().applied())
                .extracting(migration -> migration.getVersion().getVersion())
                .startsWith("1", "2");
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

        // orphanRemoval deletes the row rather than nulling its FK.
        assertThat(fileRowCount()).isEqualTo(1);
        assertThat(em.find(TestResult.class, result.getId()).files)
                .extracting(FileMetadata::getFileName)
                .containsExactly("keep.csv");
    }

    @Test
    void findByRunStatusInMatchesOnlyNonTerminalRowsAndSkipsNullStatus() {
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
    void inProjectWalksSampleToProjectAndReturnsOnlyThatProjectsResults() {
        // Each persistedTestResult() builds its own project, so the second is the control.
        TestResult inProject = persistedTestResult();
        TestResult otherProject = persistedTestResult();
        em.flush();
        em.clear();

        List<TestResult> found = repository.findAll(TestResultRepository.inProject(inProject.sample.project.getId()));

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

        Object stored = em.getEntityManager()
                .createNativeQuery("select run_status from test_result where id = :id")
                .setParameter("id", result.getId())
                .getSingleResult();
        assertThat(stored).isEqualTo("COMPLETED_WITH_GAPS");
        assertThat(em.find(TestResult.class, result.getId()).runStatus).isEqualTo(RunStatus.COMPLETED_WITH_GAPS);
    }

    @Test
    void testTypeIsStoredByItsWireNameSoTheColumnBytesAreThoseOfTheFreeFormString() {
        for (TestType type : TestType.values()) {
            TestParameter parameter = persistedTestParameter(type);
            em.flush();
            em.clear();

            assertThat(storedType(parameter)).isEqualTo(type.wireName());
            assertThat(em.find(TestParameter.class, parameter.getId()).type).isEqualTo(type);
        }
        assertThat(TestType.values()).extracting(TestType::wireName)
                .containsExactly("destructive", "cyclic", "timeCyclic");
    }

    @Test
    void aStoredTypeOutsideTheThreeFailsTheLoadNamingIt() {
        TestParameter parameter = persistedTestParameter(TestType.CYCLIC);
        em.flush();
        em.clear();
        em.getEntityManager()
                .createNativeQuery("update test_parameter set type = 'fatigue-draft' where id = :id")
                .setParameter("id", parameter.getId())
                .executeUpdate();

        assertThatThrownBy(() -> em.find(TestParameter.class, parameter.getId()))
                .hasStackTraceContaining("unknown test parameter type: fatigue-draft");
    }

    /** The three test views filter the parameter grid with {@code filterValue: TestType.X}, the constant name. */
    @Test
    void aHillaFilterOnTheConstantNameMatchesOnlyRowsOfThatType() {
        TestParameter cyclic = persistedTestParameter(TestType.CYCLIC);
        persistedTestParameter(TestType.TIME_CYCLIC);
        persistedTestParameter(TestType.DESTRUCTIVE);
        em.flush();
        em.clear();

        PropertyStringFilter filter = new PropertyStringFilter();
        filter.setPropertyId("type");
        filter.setFilterValue(TestType.CYCLIC.name());
        filter.setMatcher(PropertyStringFilter.Matcher.EQUALS);

        assertThat(testParameters.findAll(JpaFilterConverter.toSpec(filter, TestParameter.class)))
                .extracting(AbstractEntity::getId)
                .containsExactly(cyclic.getId());
    }

    private TestParameter persistedTestParameter(TestType type) {
        TestParameter parameter = new TestParameter();
        parameter.type = type;
        parameter.speed = 10;
        em.persist(parameter);
        return parameter;
    }

    private Object storedType(TestParameter parameter) {
        return em.getEntityManager()
                .createNativeQuery("select type from test_parameter where id = :id")
                .setParameter("id", parameter.getId())
                .getSingleResult();
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
        parameter.type = TestType.DESTRUCTIVE;
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
