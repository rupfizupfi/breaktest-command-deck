package ch.rupfizupfi.deck.data;

import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the statement count of a grid page (B-16). Every {@code owner} and parent link is
 * {@code EAGER} and {@code User.roles} is an eager collection, so without
 * {@code hibernate.default_batch_fetch_size} a page of N rows with distinct owners costs a select
 * per owner, per roles set, per parent — growing with N. With batching it is a fixed handful.
 *
 * <p>In-memory H2 with the dev profile's PostgreSQL mode, never the dev file (see
 * {@code ApplicationContextTest}); the Testcontainers slice keeps the {@code test} profile.
 */
@DataJpaTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:eager-graph-query-count;DB_CLOSE_DELAY=-1;MODE=PostgreSQL;DEFAULT_NULL_ORDERING=HIGH",
        "spring.jpa.properties.hibernate.generate_statistics=true"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("dev")
class EagerGraphQueryCountTest {

    private static final int ROWS = 20;
    /** Independent of {@link #ROWS}: one select per entity type in the graph, each batched. */
    private static final long MAX_STATEMENTS = 10;

    @Autowired
    TestEntityManager em;

    @Autowired
    EntityManagerFactory emf;

    @Autowired
    TestResultRepository testResults;

    @Autowired
    SampleRepository samples;

    @Autowired
    ProjectRepository projects;

    Statistics statistics;

    @BeforeEach
    void seedRowsWithDistinctOwnersAndParents() {
        for (int i = 0; i < ROWS; i++) {
            Customer customer = new Customer();
            customer.organization = "customer-" + i;
            em.persist(customer);

            Project project = new Project();
            project.owner = user("project-owner-" + i);
            project.name = "project-" + i;
            project.customer = customer;
            em.persist(project);

            Sample sample = new Sample();
            sample.owner = user("sample-owner-" + i);
            sample.project = project;
            sample.name = "sample-" + i;
            sample.yearOfManufacture = 2024;
            em.persist(sample);

            TestParameter parameter = new TestParameter();
            parameter.owner = user("parameter-owner-" + i);
            parameter.type = TestType.CYCLIC;
            parameter.speed = 10;
            em.persist(parameter);

            TestResult result = new TestResult();
            result.owner = user("result-owner-" + i);
            result.sample = sample;
            result.testParameter = parameter;
            em.persist(result);
        }
        em.flush();
        em.clear();

        statistics = emf.unwrap(SessionFactory.class).getStatistics();
        statistics.clear();
    }

    @Test
    void aTestResultPageCostsABoundedNumberOfStatements() {
        // As the grid lists it, then as OwnerSerializer touches the lazy owner during serialization.
        List<TestResult> page = testResults.findAll(PageRequest.of(0, 50)).getContent();
        page.forEach(result -> result.getOwner().getUsername());

        assertThat(page).hasSize(ROWS);
        assertThat(statements()).as("statements for a page of %d rows", ROWS).isLessThanOrEqualTo(MAX_STATEMENTS);
    }

    @Test
    void aSamplePageCostsABoundedNumberOfStatements() {
        assertThat(samples.findAll(PageRequest.of(0, 50)).getContent()).hasSize(ROWS);
        assertThat(statements()).as("statements for a page of %d rows", ROWS).isLessThanOrEqualTo(MAX_STATEMENTS);
    }

    @Test
    void aProjectPageCostsABoundedNumberOfStatements() {
        assertThat(projects.findAll(PageRequest.of(0, 50)).getContent()).hasSize(ROWS);
        assertThat(statements()).as("statements for a page of %d rows", ROWS).isLessThanOrEqualTo(MAX_STATEMENTS);
    }

    private long statements() {
        return statistics.getPrepareStatementCount();
    }

    private User user(String username) {
        User user = new User();
        user.setUsername(username);
        user.setName(username);
        user.setHashedPassword("x");
        user.setRoles(Set.of(Role.USER));
        em.persist(user);
        return user;
    }
}
