package ch.rupfizupfi.deck;

import ch.rupfizupfi.deck.api.services.SampleService;
import ch.rupfizupfi.deck.api.services.TestResultService;
import ch.rupfizupfi.deck.data.Customer;
import ch.rupfizupfi.deck.data.CustomerRepository;
import ch.rupfizupfi.deck.data.Material;
import ch.rupfizupfi.deck.data.MaterialRepository;
import ch.rupfizupfi.deck.data.Role;
import ch.rupfizupfi.deck.data.UserRepository;
import ch.rupfizupfi.deck.security.CheckUserCanOnlyAccessOwnDataAspect;
import ch.rupfizupfi.deck.security.UserDetailsServiceImpl;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.test.context.ActiveProfiles;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Boots the full cms context as {@code bootRun} does (profile {@code dev}, Flyway migrations, Hibernate
 * {@code validate}) and asserts the beans that matter exist. Booting at all proves {@code V1__baseline}
 * matches the entities on H2's PostgreSQL mode.
 *
 * <p>Except the datasource: the dev file H2 ({@code ./.data/deck}) must never be opened by a test,
 * so an in-memory H2 replaces it, guarded by {@link #datasourceIsInMemoryH2NotTheDevFile()}. The
 * override lives here because the Testcontainers slice needs {@code application-test.properties}
 * to stay datasource-free.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:cms-context-test;DB_CLOSE_DELAY=-1;MODE=PostgreSQL;DEFAULT_NULL_ORDERING=HIGH",
        "vaadin.launch-browser=false"
})
@ActiveProfiles("dev")
class ApplicationContextTest {

    @Autowired
    ApplicationContext context;

    @Autowired
    Flyway flyway;

    @Autowired
    DataSource dataSource;

    @Autowired
    UserRepository userRepository;

    @Autowired
    CustomerRepository customerRepository;

    @Autowired
    MaterialRepository materialRepository;

    @Test
    void datasourceIsInMemoryH2NotTheDevFile() throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            String url = connection.getMetaData().getURL();
            assertThat(url).contains(":mem:");
            assertThat(url).doesNotContain(".data");
        }
    }

    @Test
    void flywayMigratedTheEmptyDbFromV1WithoutBaselining() {
        // An empty schema must run V1, not be baselined past it.
        assertThat(flyway.info().applied())
                .extracting(migration -> migration.getVersion().getVersion())
                .startsWith("1", "2");
        assertThat(materialRepository.count()).isEqualTo(8);
    }

    @Test
    void theDevAccountsComeFromDevUserSeederWithTheirRoles() {
        assertThat(userRepository.findByUsername("user").getRoles()).containsExactly(Role.USER);
        assertThat(userRepository.findByUsername("admin").getRoles()).contains(Role.ADMIN);
    }

    @Test
    void referenceDataMigrationLeavesAlreadySeededTablesAlone() {
        // A database baselined at V1 already holds these rows when V2 first runs against it.
        long materials = materialRepository.count();
        long customers = customerRepository.count();

        new ResourceDatabasePopulator(new ClassPathResource("db/migration/V2__reference_data.sql")).execute(dataSource);

        assertThat(materialRepository.count()).isEqualTo(materials);
        assertThat(customerRepository.count()).isEqualTo(customers);
    }

    @Test
    void seededTablesAcceptAnApplicationInsert() {
        // V2 leaves ids to the identity column; an explicit seeded id would collide with save().
        Customer customer = new Customer();
        customer.organization = "Post-seed insert";

        Material material = new Material();
        material.name = "Post-seed insert";

        assertThat(customerRepository.save(customer).getId()).isGreaterThan(1L);
        assertThat(materialRepository.save(material).getId()).isGreaterThan(8L);
    }

    @Test
    void browserCallableCrudServicesAreRegistered() {
        assertThat(context.getBean(SampleService.class)).isNotNull();
        assertThat(context.getBean(TestResultService.class)).isNotNull();
    }

    @Test
    void userDetailsServiceIsTheCmsImplementation() {
        assertThat(context.getBean(UserDetailsService.class))
                .isInstanceOf(UserDetailsServiceImpl.class);
    }

    @Test
    void ownershipAspectIsRegistered() {
        assertThat(context.getBean(CheckUserCanOnlyAccessOwnDataAspect.class)).isNotNull();
    }
}
