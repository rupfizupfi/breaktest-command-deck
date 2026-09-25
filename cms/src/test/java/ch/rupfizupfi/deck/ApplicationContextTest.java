package ch.rupfizupfi.deck;

import ch.rupfizupfi.deck.api.services.SampleService;
import ch.rupfizupfi.deck.api.services.TestResultService;
import ch.rupfizupfi.deck.data.Customer;
import ch.rupfizupfi.deck.data.CustomerRepository;
import ch.rupfizupfi.deck.data.Material;
import ch.rupfizupfi.deck.data.MaterialRepository;
import ch.rupfizupfi.deck.data.UserRepository;
import ch.rupfizupfi.deck.security.CheckUserCanOnlyAccessOwnDataAspect;
import ch.rupfizupfi.deck.security.UserDetailsServiceImpl;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.test.context.ActiveProfiles;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Boots the full cms context exactly as {@code bootRun} does — profile {@code dev}, the production
 * wiring, the {@code data.sql} seed path — and asserts the load-bearing beans exist. An empty
 * every method here names the bean it pins.
 *
 * <p>The one deliberate divergence from a real dev boot is the datasource: profile {@code dev}
 * points at the developer's FILE-backed H2 ({@code ./.data/deck}), which a test must never open —
 * it would race a running app for the file lock and seed or sweep real data. The annotation
 * property below swaps in a throwaway in-memory H2; {@link #datasourceIsInMemoryH2NotTheDevFile()}
 * guards that the override held. The override lives here and not in the shared
 * {@code application-test.properties} because the Testcontainers persistence slice relies on that
 * file staying datasource-free.
 *
 * <p>No Vaadin suppression is needed: {@code @SpringBootTest}'s default MOCK web environment never
 * starts a servlet container, so Vaadin's dev-mode initializers (which are servlet-driven) never
 * run — and {@code vaadin-dev} is {@code developmentOnly}, absent from the test classpath anyway.
 * {@code vaadin.launch-browser=false} is defence in depth against a browser window popping open.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:cms-context-test;DB_CLOSE_DELAY=-1",
        "vaadin.launch-browser=false"
})
@ActiveProfiles("dev")
class ApplicationContextTest {

    @Autowired
    ApplicationContext context;

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
    void conditionalDataSqlInitializerIsWiredAndActuallySeededTheEmptyDb() {
        // Application#dataSourceScriptDatabaseInitializer runs data.sql only against an EMPTY
        // database; this in-memory H2 started empty, so a positive user count proves the seed
        // path ran. "Positive", not a literal: the seed's content belongs to data.sql, only
        // the wiring belongs to this test.
        assertThat(context.containsBean("dataSourceScriptDatabaseInitializer")).isTrue();
        assertThat(userRepository.count()).isPositive();
    }

    @Test
    void seededTablesAcceptAnApplicationInsert() {
        // data.sql's trailing RESTART statements move each seeded table's identity past its
        // seeded ids; without them save() collides on the primary key and throws
        // DataIntegrityViolationException. Two of the five seeded tables stand for all.
        Customer customer = new Customer();
        customer.organization = "Post-seed insert";

        Material material = new Material();
        material.name = "Post-seed insert";

        assertThat(customerRepository.save(customer).getId()).isGreaterThan(1L);
        assertThat(materialRepository.save(material).getId()).isGreaterThan(8L);
    }

    @Test
    void browserCallableCrudServicesAreRegistered() {
        // Two representatives of the CrudRepositoryService pattern every entity screen rides on.
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
        // The AOP aspect behind @CheckUserCanOnlyAccessOwnData — multi-tenancy enforcement.
        assertThat(context.getBean(CheckUserCanOnlyAccessOwnDataAspect.class)).isNotNull();
    }
}
